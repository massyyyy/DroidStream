package org.droidstream.host

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusView = TextView(this).apply {
            textSize = 16f
            text = "DroidStream Host"
        }

        val startButton = Button(this).apply {
            text = "Avvia servizio"
            setOnClickListener {
                Log.i("MainActivity", "Avvio servizio richiesto")
                startService(Intent(this@MainActivity, HostService::class.java))
                statusView.text = "Servizio in avvio..."
            }
        }

        val stopButton = Button(this).apply {
            text = "Ferma servizio"
            setOnClickListener {
                Log.i("MainActivity", "Arresto servizio richiesto")
                stopService(Intent(this@MainActivity, HostService::class.java))
                statusView.text = "Servizio fermato"
            }
        }

        layout.addView(statusView)
        layout.addView(startButton)
        layout.addView(stopButton)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        statusView.text = "DroidStream Host - premi Avvia servizio"
    }
}
