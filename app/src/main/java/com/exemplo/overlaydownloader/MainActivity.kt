package com.exemplo.overlaydownloader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var textStatus: TextView
    private lateinit var buttonEnableOverlay: Button
    private lateinit var buttonStopOverlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textStatus = findViewById(R.id.textStatus)
        buttonEnableOverlay = findViewById(R.id.buttonEnableOverlay)
        buttonStopOverlay = findViewById(R.id.buttonStopOverlay)

        buttonEnableOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startOverlayService()
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        buttonStopOverlay.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            updateStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this) && OverlayService.isRunning) {
            updateStatus()
        } else if (Settings.canDrawOverlays(this)) {
            // O usuário voltou da tela de permissão com a permissão concedida.
            // O serviço só inicia pelo botão, para não ligar sozinho sem o toque dela.
            updateStatus()
        } else {
            updateStatus()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus()
    }

    private fun updateStatus() {
        if (Settings.canDrawOverlays(this) && OverlayService.isRunning) {
            textStatus.setText(R.string.status_overlay_enabled)
        } else {
            textStatus.setText(R.string.status_overlay_disabled)
        }
    }
}
