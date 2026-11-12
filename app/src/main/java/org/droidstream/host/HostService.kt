package org.droidstream.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class HostService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var http: GameStreamHttpServer? = null
    private var https: GameStreamHttpsServer? = null
    private var mdns: MdnsAdvertiser? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (http != null) {
            Logger.i(this, "host gia' attivo: richiesta duplicata ignorata")
            return START_STICKY
        }

        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("host", getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        }

        val ip = lanIp()
        val name = HostIdentity.hostname(this)

        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "host")
                      else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setContentTitle(name)
            .setContentText("Host attivo - " + ip)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidStream::host")
            wakeLock?.acquire()
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "DroidStream::wifi")
            wifiLock?.acquire()
        }

        Logger.i(this, "=== M2a AVVIO HOST ===")
        Logger.i(this, "hostname=" + name + " uniqueid=" + HostIdentity.uniqueId(this))
        Logger.i(this, "IP LAN: " + ip)

        try {
            http = GameStreamHttpServer(this).also { it.start(HostIdentity.HTTP_PORT) }
            Logger.i(this, "HTTP in ascolto su 0.0.0.0:" + HostIdentity.HTTP_PORT)
        } catch (e: Exception) {
            http = null
            Logger.i(this, "HTTP AVVIO FALLITO: " + e.javaClass.simpleName + ": " + e.message)
        }

        try {
            val ssl = TlsIdentity.sslContext(this)
            https = GameStreamHttpsServer(this).also { it.start(HostIdentity.HTTPS_PORT, ssl) }
            Logger.i(this, "HTTPS in ascolto su 0.0.0.0:" + HostIdentity.HTTPS_PORT)
        } catch (e: Exception) {
            https = null
            Logger.i(this, "HTTPS AVVIO FALLITO: " + e.javaClass.simpleName + ": " + e.message)
        }

        if (mdns == null) {
            mdns = MdnsAdvertiser(this).also { it.start(name, HostIdentity.HTTP_PORT) }
        }

        Thread {
            try {
                Thread.sleep(500)
                Logger.i(this, "SELF-TEST /serverinfo:\n" + (http?.selfTest() ?: "server non attivo"))
            } catch (e: Exception) {}
        }.apply { isDaemon = true }.start()

        return START_STICKY
    }

    private fun lanIp(): String {
        return try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION") val a = wm.connectionInfo.ipAddress
            if (a == 0) "0.0.0.0"
            else "%d.%d.%d.%d".format(a and 0xff, (a shr 8) and 0xff, (a shr 16) and 0xff, (a shr 24) and 0xff)
        } catch (e: Exception) { "sconosciuto" }
    }

    override fun onDestroy() {
        mdns?.stop(); mdns = null
        https?.stop(); https = null
        http?.stop(); http = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        Logger.i(this, "=== HOST FERMATO ===")
        super.onDestroy()
    }
}
