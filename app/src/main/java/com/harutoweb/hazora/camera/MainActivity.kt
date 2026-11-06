package com.harutoweb.hazora.camera

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = TextView(this).apply {
            text = "Hazora Mobile Camera\n\nAndroid project is ready for development."
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        setContentView(message)
    }
}
