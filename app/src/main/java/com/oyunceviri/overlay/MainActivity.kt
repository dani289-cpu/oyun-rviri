package com.oyunceviri.overlay

import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var apiInput: EditText
    private val REQ_CAPTURE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ayarlar", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        apiInput = EditText(this).apply {
            hint = "Gemini API Anahtarını yapıştır"
            setText(prefs.getString("api_key", ""))
        }

        val startBtn = Button(this).apply {
            text = "Çeviriyi Başlat"
            setOnClickListener {
                val key = apiInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(context, "Lütfen bir API anahtarı gir!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("api_key", key).apply()

                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(context, "Üstte gösterme iznini açıp tekrar bas", Toast.LENGTH_LONG).show()
                } else {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
                }
            }
        }

        val stopBtn = Button(this).apply {
            text = "Çeviriyi Durdur"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(context, "Çeviri durduruldu", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(apiInput)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("API_KEY", apiInput.text.toString().trim())
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            moveTaskToBack(true)
        }
    }
}
