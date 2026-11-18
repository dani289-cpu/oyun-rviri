package com.oyunceviri.overlay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.*
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.view.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class TranslatedBox(val rect: Rect, val text: String)

class OverlayView(context: Context) : View(context) {
    private val bgPaint = Paint().apply {
        color = Color.argb(230, 15, 15, 15)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = 26f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    var boxes: List<TranslatedBox> = emptyList()
    var statusText: String = "Çeviri Başlatıldı..."

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (statusText.isNotEmpty()) {
            canvas.drawRoundRect(RectF(20f, 60f, 700f, 115f), 8f, 8f, bgPaint)
            canvas.drawText(statusText, 32f, 96f, statusPaint)
        }
        for (item in boxes) {
            val r = item.rect
            // Koordinatları orijinal altyazı alanına tam oturtmak için hizalama düzeltmesi
            val adjustedRect = RectF(
                r.left.toFloat().coerceAtLeast(10f),
                (r.top - 12f).coerceAtLeast(10f),
                r.right.toFloat().coerceAtMost(canvas.width.toFloat() - 10f),
                r.bottom.toFloat() + 12f
            )
            canvas.drawRoundRect(adjustedRect, 8f, 8f, bgPaint)
            textPaint.textSize = (adjustedRect.height() * 0.65f).coerceIn(24f, 52f)
            canvas.drawText(item.text, adjustedRect.left + 12f, adjustedRect.bottom - 12f, textPaint)
        }
    }
}

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cpuExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastRawText = ""
    private var apiKey = ""
    private var isProcessing = false
    private var isRunning = false
    private var activeModel = "gemini-3.5-flash-lite"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        apiKey = intent?.getStringExtra("API_KEY")?.trim() ?: ""
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data: Intent = intent.getParcelableExtra("DATA") ?: return START_NOT_STICKY

        startForegroundNotification()
        setupOverlayWindow()

        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    isRunning = false
                    virtualDisplay?.release()
                    imageReader?.close()
                }
            }, mainHandler)

            isRunning = true
            startScreenLoop()
        } catch (e: Exception) {
            updateStatus("Başlatma Hatası: ${e.message}")
        }
        return START_STICKY
    }

    private fun updateStatus(msg: String) {
        mainHandler.post {
            if (::overlayView.isInitialized) {
                overlayView.statusText = msg
                overlayView.invalidate()
            }
        }
    }

    private fun startForegroundNotification() {
        val ch = "ceviri_kanal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(ch, "Oyun Ceviri", NotificationManager.IMPORTANCE_LOW))
        }
        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, ch) else Notification.Builder(this)
        val notif = nb.setContentTitle("Oyun Çeviri Aktif").setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        overlayView = OverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayView, params)
    }

    private fun startScreenLoop() {
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "OyunCeviri", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, mainHandler
        )
        updateStatus("Oyun Ekranı Bekleniyor...")

        val runnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                captureAndAnalyze()
                // Kota aşımını (429) önlemek için tarama aralığı 4.5 saniyeye çıkarıldı
                mainHandler.postDelayed(this, 4500)
            }
        }
        mainHandler.postDelayed(runnable, 2000)
    }

    private fun captureAndAnalyze() {
        if (isProcessing) return
        val image = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null } ?: return
        isProcessing = true

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val fullBitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(buffer)
            val bitmap = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height)
            image.close()

            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(cpuExecutor) { visionText ->
                    // Sadece ekranın alt kısmındaki (oyun altyazı bölgesi) metinleri hedefle
                    val screenHeight = bitmap.height
                    val lines = visionText.textBlocks.flatMap { it.lines }
                        .filter { line ->
                            val t = line.text.trim()
                            val box = line.boundingBox
                            // Altyazılar genelde ekranın alt yarısında yer alır
                            box != null && box.top > (screenHeight * 0.55) && t.length > 3 &&
                                    !t.startsWith("Hata") && !t.startsWith("Çeviri") && !t.startsWith("Oyun")
                        }

                    val currentRaw = lines.joinToString("|") { it.text }

                    if (lines.isEmpty()) {
                        lastRawText = ""
                        mainHandler.post {
                            overlayView.boxes = emptyList()
                            overlayView.statusText = ""
                            overlayView.invalidate()
                        }
                        isProcessing = false
                        return@addOnSuccessListener
                    }

                    if (currentRaw == lastRawText) {
                        isProcessing = false
                        return@addOnSuccessListener
                    }

                    lastRawText = currentRaw
                    updateStatus("Çevriliyor...")

                    cpuExecutor.execute {
                        val translatedBoxes = translateLinesWithGemini(lines.take(4).map { Pair(it.boundingBox ?: Rect(), it.text) })
                        mainHandler.post {
                            if (translatedBoxes.isNotEmpty()) {
                                overlayView.boxes = translatedBoxes
                                overlayView.statusText = ""
                                overlayView.invalidate()
                            }
                        }
                        isProcessing = false
                    }
                }
                .addOnFailureListener {
                    isProcessing = false
                }
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            isProcessing = false
        }
    }

    private fun translateLinesWithGemini(items: List<Pair<Rect, String>>): List<TranslatedBox> {
        return try {
            val joined = items.mapIndexed { i, pair -> "[$i] ${pair.second}" }.joinToString("\n")
            val prompt = "Sen profesyonel bir oyun çevirmenisin. Aşağıdaki oyun altyazılarını akıcı, doğal ve oyunun atmosferine uygun bir şekilde Türkçeye çevir. Sadece [numara] Çeviri formatında yanıt ver:\n$joined"

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true

            val body = JSONObject().put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                val msg = try { JSONObject(err).getJSONObject("error").getString("message") } catch (_: Exception) { err }
                updateStatus("Hata ($code): ${msg.take(35)}")
                return emptyList()
            }

            val response = conn.inputStream.bufferedReader().readText()
            val reply = JSONObject(response).getJSONArray("candidates")
                .getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text")

            val replyLines = reply.lines().filter { it.isNotBlank() }
            items.mapIndexed { index, pair ->
                val cleanText = replyLines.getOrNull(index)?.replace(Regex("^\\[\\d+\\]\\s*"), "") ?: pair.second
                TranslatedBox(pair.first, cleanText)
            }
        } catch (e: Exception) {
            updateStatus("Bağlantı Hatası")
            emptyList()
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
