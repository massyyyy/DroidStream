package org.droidstream.host

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private const val TAG = "DroidStream"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(ctx.filesDir, "droidstream.log")
            f.appendText(fmt.format(Date()) + " " + msg + "\n")
            if (f.length() > 512000) f.writeText(f.readText().takeLast(256000))
        } catch (e: Exception) {
            Log.w(TAG, "log file non scrivibile: " + e.message)
        }
    }

    fun dump(ctx: Context): String {
        return try {
            val t = File(ctx.filesDir, "droidstream.log").readText()
            if (t.isEmpty()) "(log vuoto)" else t
        } catch (e: Exception) {
            "(log non leggibile: " + e.message + ")"
        }
    }
}