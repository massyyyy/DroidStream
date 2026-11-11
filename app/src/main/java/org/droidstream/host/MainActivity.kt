package org.droidstream.host

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        out = findViewById(R.id.output)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<Button>(R.id.btn_diag).setOnClickListener { runDiagnostics() }
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            val i = Intent(this, HostService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, HostService::class.java))
        }
        findViewById<Button>(R.id.btn_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DroidStream", Logger.dump(this)))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        runDiagnostics()
    }

    private fun runDiagnostics() {
        out.setText(R.string.working)
        Thread {
            val report = Diagnostics.collect(this)
            Logger.i(this, "diagnostica completata")
            runOnUiThread { out.text = report }
        }.start()
    }
}