package org.droidstream.host

import android.content.Context
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object InputManager {
    private const val TAG = "InputManager"

    const val TYPE_MOUSE_ABS = 1
    const val TYPE_MOUSE_DOWN = 2
    const val TYPE_MOUSE_UP = 3
    const val TYPE_TOUCH_DOWN = 4
    const val TYPE_TOUCH_UP = 5
    const val TYPE_TOUCH_MOVE = 6
    const val TYPE_KEY_DOWN = 7
    const val TYPE_KEY_UP = 8
    const val TYPE_SCROLL = 9

    private var screenWidth = 1280
    private var screenHeight = 720

    private var currentX = 0
    private var currentY = 0
    private var downX = 0
    private var downY = 0
    private var downTime = 0L

    private var suProcess: Process? = null
    private var suWriter: BufferedWriter? = null
    private var isRootAvailable = false
    private var executor: ExecutorService? = null

    fun init(context: Context) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                screenWidth = dm.widthPixels
                screenHeight = dm.heightPixels
            }
        } catch (e: Exception) {
            Log.w(TAG, "Impossibile ottenere risoluzione schermo reale: ${e.message}")
        }

        currentX = screenWidth / 2
        currentY = screenHeight / 2

        if (executor == null || executor!!.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }

        executor?.execute {
            setupRootShell()
        }
    }

    private fun setupRootShell() {
        try {
            val p = Runtime.getRuntime().exec("su")
            val writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            writer.write("echo ok\n")
            writer.flush()
            suProcess = p
            suWriter = writer
            isRootAvailable = true
            Log.i(TAG, "Shell root inizializzata per iniezione input (${screenWidth}x${screenHeight})")
        } catch (e: Exception) {
            isRootAvailable = false
            suProcess = null
            suWriter = null
            Log.w(TAG, "Accesso root non disponibile: l'input remoto richiede permessi root su Android 6+")
        }
    }

    fun onInput(type: Int, x: Int, y: Int, button: Int, keyCode: Int) {
        val exec = executor ?: return
        if (exec.isShutdown) return

        exec.execute {
            handleEvent(type, x, y, button, keyCode)
        }
    }

    private fun handleEvent(type: Int, x: Int, y: Int, button: Int, keyCode: Int) {
        when (type) {
            TYPE_MOUSE_ABS -> {
                currentX = ((x.toLong() * screenWidth) / 10000L).toInt().coerceIn(0, screenWidth - 1)
                currentY = ((y.toLong() * screenHeight) / 10000L).toInt().coerceIn(0, screenHeight - 1)
            }
            TYPE_TOUCH_MOVE -> {
                currentX = ((x.toLong() * screenWidth) / 10000L).toInt().coerceIn(0, screenWidth - 1)
                currentY = ((y.toLong() * screenHeight) / 10000L).toInt().coerceIn(0, screenHeight - 1)
            }
            TYPE_MOUSE_DOWN, TYPE_TOUCH_DOWN -> {
                if (x != 0 || y != 0) {
                    currentX = ((x.toLong() * screenWidth) / 10000L).toInt().coerceIn(0, screenWidth - 1)
                    currentY = ((y.toLong() * screenHeight) / 10000L).toInt().coerceIn(0, screenHeight - 1)
                }
                downX = currentX
                downY = currentY
                downTime = SystemClock.uptimeMillis()
            }
            TYPE_MOUSE_UP, TYPE_TOUCH_UP -> {
                if (x != 0 || y != 0) {
                    currentX = ((x.toLong() * screenWidth) / 10000L).toInt().coerceIn(0, screenWidth - 1)
                    currentY = ((y.toLong() * screenHeight) / 10000L).toInt().coerceIn(0, screenHeight - 1)
                }
                val now = SystemClock.uptimeMillis()
                val dx = currentX - downX
                val dy = currentY - downY
                val distSq = dx * dx + dy * dy

                if (distSq < 900) { // Meno di 30 pixel: tap
                    sendShellCommand("input tap $currentX $currentY\n")
                } else { // Swipe o trascinamento
                    val duration = (now - downTime).coerceIn(100L, 800L)
                    sendShellCommand("input swipe $downX $downY $currentX $currentY $duration\n")
                }
            }
            TYPE_SCROLL -> {
                val scrollDelta = y * 50
                val startY = (screenHeight / 2).coerceIn(0, screenHeight - 1)
                val endY = (startY + scrollDelta).coerceIn(0, screenHeight - 1)
                val midX = screenWidth / 2
                sendShellCommand("input swipe $midX $startY $midX $endY 150\n")
            }
            TYPE_KEY_DOWN -> {
                val androidKey = mapKeyCode(keyCode)
                if (androidKey > 0) {
                    sendShellCommand("input keyevent $androidKey\n")
                }
            }
        }
    }

    private fun mapKeyCode(code: Int): Int {
        return when (code) {
            0x1B -> 4   // ESC -> KEYCODE_BACK
            0x24 -> 3   // HOME -> KEYCODE_HOME
            0x0D -> 66  // ENTER -> KEYCODE_ENTER
            0x20 -> 62  // SPACE -> KEYCODE_SPACE
            0x08 -> 67  // BACKSPACE -> KEYCODE_DEL
            0x09 -> 61  // TAB -> KEYCODE_TAB
            0x26 -> 19  // UP -> KEYCODE_DPAD_UP
            0x28 -> 20  // DOWN -> KEYCODE_DPAD_DOWN
            0x25 -> 21  // LEFT -> KEYCODE_DPAD_LEFT
            0x27 -> 22  // RIGHT -> KEYCODE_DPAD_RIGHT
            0xAF -> 24  // VOL_UP -> KEYCODE_VOLUME_UP
            0xAE -> 25  // VOL_DOWN -> KEYCODE_VOLUME_DOWN
            else -> 0
        }
    }

    private fun sendShellCommand(cmd: String) {
        val writer = suWriter ?: return
        try {
            writer.write(cmd)
            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Errore scrittura su root shell: ${e.message}")
            isRootAvailable = false
            suWriter = null
        }
    }

    fun stop() {
        executor?.execute {
            try {
                suWriter?.write("exit\n")
                suWriter?.flush()
                suWriter?.close()
            } catch (e: Exception) {}
            suWriter = null
            try {
                suProcess?.destroy()
            } catch (e: Exception) {}
            suProcess = null
            isRootAvailable = false
        }
        executor?.shutdown()
        executor = null
    }
}
