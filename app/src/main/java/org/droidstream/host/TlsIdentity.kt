package org.droidstream.host

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object TlsIdentity {

    private const val ALIAS = "droidstream"
    private const val KS_FILE = "keystore.p12"
    private const val KS_PASS = "droidstream-local"

    fun sslContext(ctx: Context): SSLContext {
        val ks = loadOrCreate(ctx)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KS_PASS.toCharArray())
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, null, null)
        return ssl
    }

    private fun loadOrCreate(ctx: Context): KeyStore {
        val file = File(ctx.filesDir, KS_FILE)
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            try {
                FileInputStream(file).use { ks.load(it, KS_PASS.toCharArray()) }
                if (ks.getKey(ALIAS, KS_PASS.toCharArray()) != null) {
                    Logger.i(ctx, "certificato TLS caricato da " + file.name)
                    return ks
                }
            } catch (e: Exception) {
                Logger.i(ctx, "keystore illeggibile, rigenero: " + e.message)
            }
        }
        val gen = generate()
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, gen.first, KS_PASS.toCharArray(), arrayOf(gen.second))
        FileOutputStream(file).use { ks.store(it, KS_PASS.toCharArray()) }
        Logger.i(ctx, "certificato TLS generato e salvato in " + file.name)
        return ks
    }

    private fun generate(): Pair<PrivateKey, X509Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp: KeyPair = kpg.generateKeyPair()
        val subject = X500Name("CN=DroidStream Host")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date(System.currentTimeMillis() - 86400000L)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 86400000L)
        val builder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, kp.public)
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return Pair(kp.private, cert)
    }
}
