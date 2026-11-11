package org.droidstream.host

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class MdnsAdvertiser(private val ctx: Context) {

    private var nm: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun start(name: String, port: Int) {
        if (listener != null) return
        val manager = ctx.applicationContext.getSystemService(NsdManager::class.java)
        if (manager == null) {
            Logger.i(ctx, "mDNS: NsdManager non disponibile")
            return
        }

        // Verificato: Sunshine platform/common.h:1324 -> "_nvstream._tcp"
        // NIENTE apply{}: il receiver nascondeva il campo serviceType (bug M1)
        val type = "_nvstream._tcp."
        val info = NsdServiceInfo()
        info.serviceName = name
        info.setServiceType(type)
        info.setPort(port)

        Logger.i(ctx, "mDNS: registro name=" + info.serviceName + " type=" + info.serviceType + " port=" + port)

        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                Logger.i(ctx, "mDNS REGISTRATO: " + si.serviceName)
            }
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                Logger.i(ctx, "mDNS FALLITO errorCode=" + errorCode)
                listener = null
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {
                Logger.i(ctx, "mDNS rimosso")
            }
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                Logger.i(ctx, "mDNS unregistration errorCode=" + errorCode)
            }
        }
        listener = l
        nm = manager
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Logger.i(ctx, "mDNS register eccezione: " + e.message)
            listener = null
        }
    }

    fun stop() {
        try { listener?.let { nm?.unregisterService(it) } } catch (e: Exception) {}
        listener = null
        nm = null
    }
}
