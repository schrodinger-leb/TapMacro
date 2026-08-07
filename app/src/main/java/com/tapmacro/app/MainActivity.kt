package com.tapmacro.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find 'Tap Macro' in the list and enable it", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnStartController).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant the overlay permission first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Floating controller started. You can minimize this app.", Toast.LENGTH_LONG).show()
        }
    }
}
