package org.droidstream.host

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class MdnsAdvertiser(private val ctx: Context) {

    // Verificato: Sunshine platform/common.h:1324 -> "_nvstream._tcp"
    private val serviceType = "_nvstream._tcp."
    private var nm: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun start(name: String, port: Int) {
        if (listener != null) return
        val manager = ctx.applicationContext.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = name
            setServiceType(serviceType)
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Logger.i(ctx, "mDNS REGISTRATO: " + info.serviceName + " " + serviceType + " porta " + port)
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.i(ctx, "mDNS FALLITO errorCode=" + errorCode)
                listener = null
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Logger.i(ctx, "mDNS rimosso")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
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
