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
        color = Color.argb(220, 18, 18, 18)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    var boxes: List<TranslatedBox> = emptyList()
    var statusText: String = "Çeviri Başlatılıyor..."

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (statusText.isNotEmpty()) {
            canvas.drawRoundRect(RectF(16f, 70f, 920f, 125f), 8f, 8f, bgPaint)
            canvas.drawText(statusText, 28f, 108f, statusPaint)
        }
        for (item in boxes) {
            val r = item.rect
            canvas.drawRoundRect(RectF(r), 6f, 6f, bgPaint)
            textPaint.textSize = (r.height() * 0.75f).coerceIn(22f, 64f)
            canvas.drawText(item.text, r.left.toFloat() + 4f, r.bottom.toFloat() - 6f, textPaint)
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

    // Google AI Studio güncel modelleri + eski yedekler
    private val modelList = mutableListOf(
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash",
        "gemini-3.6-flash",
        "gemini-3.1-flash-lite",
        "gemini-3-flash-preview",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite"
    )
    private var activeModel: String? = null

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
        updateStatus("Ekran Taranıyor...")

        val runnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                captureAndAnalyze()
                mainHandler.postDelayed(this, 900)
            }
        }
        mainHandler.postDelayed(runnable, 500)
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
                    val lines = visionText.textBlocks.flatMap { it.lines }
                        .filter {
                            val t = it.text.trim()
                            t.length > 2 && !t.startsWith("Ekran") && !t.startsWith("Çeviri") && !t.startsWith("Hata") && !t.startsWith("Metin") && !t.startsWith("Model")
                        }

                    val currentRaw = lines.joinToString("|") { it.text }

                    if (lines.isEmpty()) {
                        lastRawText = ""
                        mainHandler.post {
                            overlayView.boxes = emptyList()
                            overlayView.statusText = "Metin Bekleniyor..."
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
                    updateStatus("Çevriliyor (${lines.size} satır)...")

                    cpuExecutor.execute {
                        val translatedBoxes = translateLinesWithGemini(lines.take(12).map { Pair(it.boundingBox ?: Rect(), it.text) })
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
                .addOnFailureListener { e ->
                    updateStatus("OCR Hatası: ${e.message}")
                    isProcessing = false
                }
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            updateStatus("Ekran Hatası: ${e.message}")
            isProcessing = false
        }
    }

    // Hesapta açık olan modelleri Google sunucusundan otomatik çeken yedek fonksiyon
    private fun fetchAvailableModelsFromApi() {
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            if (conn.responseCode in 200..299) {
                val res = conn.inputStream.bufferedReader().readText()
                val arr = JSONObject(res).optJSONArray("models") ?: return
                val discovered = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val name = m.optString("name", "").removePrefix("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                    if (methods.contains("generateContent") && name.contains("gemini") && !name.contains("tts") && !name.contains("image")) {
                        discovered.add(name)
                    }
                }
                // Flash modellerine öncelik ver
                discovered.sortBy { if (it.contains("flash")) 0 else 1 }
                for (d in discovered.reversed()) {
                    if (!modelList.contains(d)) modelList.add(0, d)
                }
            }
        } catch (_: Exception) {}
    }

    private fun translateLinesWithGemini(items: List<Pair<Rect, String>>): List<TranslatedBox> {
        val joined = items.mapIndexed { i, pair -> "[$i] ${pair.second}" }.joinToString("\n")
        val prompt = "Sen bir oyun çevirmenisin. Aşağıdaki oyun metinlerini askeri ve oyun terimlerine sadık kalarak doğal Türkçeye çevir. Sadece [numara] Çeviri formatında yanıt ver:\n$joined"

        val candidates = if (activeModel != null) listOf(activeModel!!) + modelList.filter { it != activeModel } else modelList.toList()

        for (modelName in candidates) {
            val result = tryGenerate(modelName, prompt, items)
            if (result != null) {
                activeModel = modelName
                return result
            }
        }

        // Eğer listedeki hiçbir model 200 dönmediyse, doğrudan sunucudan açık modelleri sor ve tekrar dene
        updateStatus("Modeller sunucudan sorgulanıyor...")
        fetchAvailableModelsFromApi()
        for (modelName in modelList) {
            val result = tryGenerate(modelName, prompt, items)
            if (result != null) {
                activeModel = modelName
                return result
            }
        }

        return emptyList()
    }

    private fun tryGenerate(modelName: String, prompt: String, items: List<Pair<Rect, String>>): List<TranslatedBox>? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent")
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
            if (code == 404) {
                return null // Bu model bu projede kapalıysa sıradaki modele geç
            }
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                val msg = try { JSONObject(err).getJSONObject("error").getString("message") } catch (_: Exception) { err }
                updateStatus("Hata ($code - $modelName): ${msg.take(30)}")
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
            updateStatus("Bağlantı Hatası: ${e.message?.take(30)}")
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
