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
        color = Color.argb(215, 18, 18, 18)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    var boxes: List<TranslatedBox> = emptyList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in boxes) {
            val r = item.rect
            canvas.drawRoundRect(RectF(r), 6f, 6f, bgPaint)
            textPaint.textSize = (r.height() * 0.78f).coerceIn(22f, 68f)
            canvas.drawText(item.text, r.left.toFloat() + 4f, r.bottom.toFloat() - 6f, textPaint)
        }
    }
}

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cpuExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastRawText = ""
    private var apiKey = ""
    private var isProcessing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        apiKey = intent?.getStringExtra("API_KEY") ?: ""
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data: Intent = intent.getParcelableExtra("DATA") ?: return START_NOT_STICKY

        startForegroundNotification()
        setupOverlayWindow()
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)
        startScreenLoop()
        return START_STICKY
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
        mediaProjection?.createVirtualDisplay(
            "OyunCeviri", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )

        val runnable = object : Runnable {
            override fun run() {
                captureAndAnalyze()
                mainHandler.postDelayed(this, 450)
            }
        }
        mainHandler.post(runnable)
    }

    private fun captureAndAnalyze() {
        if (isProcessing) return
        val image = imageReader?.acquireLatestImage() ?: return
        isProcessing = true

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(cpuExecutor) { visionText ->
                val lines = visionText.textBlocks.flatMap { it.lines }.filter { it.text.length > 2 }
                val currentRaw = lines.joinToString("|") { it.text }

                if (lines.isEmpty()) {
                    lastRawText = ""
                    mainHandler.post {
                        overlayView.boxes = emptyList()
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
                cpuExecutor.execute {
                    val translatedBoxes = translateLinesWithGemini(lines.map { Pair(it.boundingBox ?: Rect(), it.text) })
                    mainHandler.post {
                        overlayView.boxes = translatedBoxes
                        overlayView.invalidate()
                    }
                    isProcessing = false
                }
            }
            .addOnFailureListener { isProcessing = false }
    }

    private fun translateLinesWithGemini(items: List<Pair<Rect, String>>): List<TranslatedBox> {
        return try {
            val joined = items.mapIndexed { i, pair -> "[$i] ${pair.second}" }.joinToString("\n")
            val prompt = "Sen bir oyun çevirmenisin. Aşağıdaki oyun metinlerini askeri ve oyun terimlerine sadık kalarak (örneğin extraction point = tahliye noktası) doğal Türkçeye çevir. Sadece [numara] Çeviri formatında yanıt ver:\n$joined"

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

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
            emptyList()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
