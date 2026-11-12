package org.droidstream.host

import android.content.Context
import android.content.Intent
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object Pairing {

    private const val PHASE_NONE = 0
    private const val PHASE_CERT = 1
    private const val PHASE_CLIENT_CHALLENGE = 2
    private const val PHASE_SERVER_RESP = 3
    private const val TIMEOUT_MS = 120000L
    private const val KS_PASS = "droidstream-local"

    class Session(val uniqueId: String, val clientName: String, val clientCertPem: String) {
        var phase = PHASE_NONE
        var aesKey: ByteArray? = null
        var serverSecret: ByteArray? = null
        var serverChallenge: ByteArray? = null
        var clientHash: ByteArray? = null
        val createdAt = System.currentTimeMillis()
        val pinLatch = CountDownLatch(1)
        var pin: String? = null
    }

    private val sessions = HashMap<String, Session>()
    private var pendingPin: Session? = null
    private var cachedCert: X509Certificate? = null
    private var cachedKey: PrivateKey? = null

    // ---------- utilita' ----------
    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02X", x))
        return sb.toString()
    }

    fun unhex(s: String): ByteArray {
        val t = s.trim()
        val out = ByteArray(t.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(t[i * 2], 16) shl 4) + Character.digit(t[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    fun parseQuery(q: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (q.isEmpty()) return out
        for (part in q.split('&')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            out[java.net.URLDecoder.decode(part.substring(0, i), "UTF-8")] =
                java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }
        return out
    }

    private fun loadKeys(ctx: Context) {
        if (cachedCert != null) return
        val ks = KeyStore.getInstance("PKCS12")
        File(ctx.filesDir, "keystore.p12").inputStream().use { ks.load(it, KS_PASS.toCharArray()) }
        cachedCert = ks.getCertificate("droidstream") as X509Certificate
        cachedKey = ks.getKey("droidstream", KS_PASS.toCharArray())
    }

    // ---------- crypto (verificata: crypto.cpp:325-343, PairingManager.java:95-165) ----------
    private fun sha256(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(d)

    private fun aesKey(salt: ByteArray, pin: String): ByteArray =
        sha256(salt + pin.toByteArray(Charsets.UTF_8)).copyOf(16)

    private fun aesEcb(mode: Int, key: ByteArray, input: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(input)
    }

    private fun rand(n: Int): ByteArray {
        val b = ByteArray(n); SecureRandom().nextBytes(b); return b
    }

    private fun signBytes(data: ByteArray, ctx: Context): ByteArray {
        loadKeys(ctx)
        val s = Signature.getInstance("SHA256withRSA")
        s.initSign(cachedKey)
        s.update(data)
        return s.sign()
    }

    // ---------- client autorizzati ----------
    fun isAuthorized(uid: String): Boolean =
        ctx0?.let { it.getSharedPreferences("droidstream_host", Context.MODE_PRIVATE)
            .getStringSet("authorized", emptySet())?.contains(uid) } ?: false

    private var ctx0: Context? = null

    private fun addAuthorized(ctx: Context, sess: Session): Boolean {
        return try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(sess.clientCertPem.byteInputStream())
            val p = ctx.getSharedPreferences("droidstream_host", Context.MODE_PRIVATE)
            val set = HashSet(p.getStringSet("authorized", emptySet()))
            set.add(sess.uniqueId)
            p.edit().putStringSet("authorized", set)
                .putString("cert_" + sess.uniqueId, sess.clientCertPem).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun submitPin(pin: String) {
        val s = pendingPin ?: return
        s.pin = pin
        s.pinLatch.countDown()
    }

    // ---------- XML ----------
    private fun xml(status: Int, msg: String?, fields: Map<String, String>?): Pair<Int, String> {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root status_code=\"")
        sb.append(status).append("\"")
        if (msg != null) sb.append(" status_message=\"").append(msg).append("\"")
        sb.append(">")
        fields?.forEach { sb.append("<").append(it.key).append(">").append(it.value).append("</").append(it.key).append(">") }
        sb.append("</root>")
        return Pair(status, sb.toString())
    }

    fun applistXml(): Pair<Int, String> =
        xml(200, null, mapOf(
            "App" to ""))
        .let {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<root status_code=\"200\"><App><IsHdrSupported>0</IsHdrSupported>" +
                "<AppTitle>Android Screen</AppTitle><ID>1</ID></App></root>"
            Pair(200, body)
        }

    fun serverInfoXml(ctx: Context, localIp: String, https: Boolean, clientUid: String?,
                      builder: (pairStatus: Int) -> String): Pair<Int, String> =
        Pair(200, builder(if (https && clientUid != null && isAuthorized(clientUid)) 1 else 0))

    // ---------- dispatcher (nvhttp.cpp:1082-1146) ----------
    fun handlePair(ctx: Context, q: Map<String, String>, https: Boolean): Pair<Int, String> {
        ctx0 = ctx.applicationContext
        val uid = q["uniqueid"]
        if (uid.isNullOrEmpty()) return xml(400, "Missing uniqueid parameter", null)
        expire()
        when (q["phrase"]) {
            "getservercert" -> return getServerCert(ctx, q, uid)
            "pairchallenge" -> return xml(200, null, mapOf("paired" to "1"))
        }
        val sess = sessions[uid] ?: return xml(400, "Invalid uniqueid", null)
        return when {
            q.containsKey("clientchallenge") -> clientChallenge(ctx, sess, q["clientchallenge"]!!)
            q.containsKey("serverchallengeresp") -> serverChallengeResp(ctx, sess, q["serverchallengeresp"]!!)
            q.containsKey("clientpairingsecret") -> clientPairingSecret(ctx, sess, q["clientpairingsecret"]!!)
            else -> { sessions.remove(uid); xml(400, "Invalid pairing request", null) }
        }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        val dead = sessions.values.filter { now - it.createdAt > TIMEOUT_MS }.map { it.uniqueId }
        dead.forEach { sessions.remove(it) }
    }

    // fase 1 (nvhttp.cpp:752-774)
    private fun getServerCert(ctx: Context, q: Map<String, String>, uid: String): Pair<Int, String> {
        val saltHex = q["salt"] ?: return xml(400, "Missing salt", null)
        val certHex = q["clientcert"] ?: return xml(400, "Missing clientcert", null)
        if (saltHex.length < 32) return xml(400, "Salt too short", null)
        val pem = String(unhex(certHex), Charsets.UTF_8)
        val sess = Session(uid, q["devicename"] ?: "Moonlight", pem)
        sessions[uid] = sess
        pendingPin = sess
        try {
            ctx.startActivity(Intent(ctx, PinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {}
        val got = sess.pinLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        pendingPin = null
        if (!got || sess.pin == null) {
            sessions.remove(uid)
            return xml(400, "PIN timeout", null)
        }
        loadKeys(ctx)
        sess.aesKey = aesKey(unhex(saltHex).copyOf(16), sess.pin!!)
        sess.phase = PHASE_CERT
        return xml(200, null, mapOf("paired" to "1", "plaincert" to hex(cachedCert!!.encoded)))
    }

    // fase 2 (nvhttp.cpp:783-824)
    private fun clientChallenge(ctx: Context, sess: Session, challengeHex: String): Pair<Int, String> {
        if (sess.phase != PHASE_CERT) { sessions.remove(sess.uniqueId); return xml(400, "Out of order", null) }
        val key = sess.aesKey ?: return xml(400, "Cipher key not set", null)
        loadKeys(ctx)
        val dec = aesEcb(Cipher.DECRYPT_MODE, key, unhex(challengeHex))
        val sign = cachedCert!!.sigBytes
        val secret = rand(16)
        val hash = sha256(dec + sign + secret)
        val challenge = rand(16)
        val enc = aesEcb(Cipher.ENCRYPT_MODE, key, hash + challenge)
        sess.serverSecret = secret
        sess.serverChallenge = challenge
        sess.phase = PHASE_CLIENT_CHALLENGE
        return xml(200, null, mapOf("paired" to "1", "challengeresponse" to hex(enc)))
    }

    // fase 3 (nvhttp.cpp:833-860)
    private fun serverChallengeResp(ctx: Context, sess: Session, respHex: String): Pair<Int, String> {
        if (sess.phase != PHASE_CLIENT_CHALLENGE) { sessions.remove(sess.uniqueId); return xml(400, "Out of order", null) }
        val key = sess.aesKey ?: return xml(400, "Cipher key not set", null)
        val secret = sess.serverSecret ?: return xml(400, "serversecret not set", null)
        sess.clientHash = aesEcb(Cipher.DECRYPT_MODE, key, unhex(respHex))
        val sig = signBytes(secret, ctx)
        sess.phase = PHASE_SERVER_RESP
        return xml(200, null, mapOf("paired" to "1", "pairingsecret" to hex(secret + sig)))
    }

    // fase 4 (nvhttp.cpp:869-914)
    private fun clientPairingSecret(ctx: Context, sess: Session, secretHex: String): Pair<Int, String> {
        if (sess.phase != PHASE_SERVER_RESP) { sessions.remove(sess.uniqueId); return xml(400, "Out of order", null) }
        val all = unhex(secretHex)
        if (all.size <= 16) { sessions.remove(sess.uniqueId); return xml(400, "Client pairing secret too short", null) }
        val secret = all.copyOf(16)
        val sign = all.copyOfRange(16, all.size)
        val paired = try {
            val clientCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(sess.clientCertPem.byteInputStream()) as X509Certificate
            val data = (sess.serverChallenge ?: ByteArray(0)) + clientCert.sigBytes + secret
            val sameHash = sha256(data).contentEquals(sess.clientHash ?: ByteArray(0))
            val v = Signature.getInstance("SHA256withRSA")
            v.initVerify(clientCert.publicKey)
            v.update(secret)
            val verify = v.verify(sign)
            sameHash && verify && addAuthorized(ctx, sess)
        } catch (e: Exception) {
            false
        }
        sessions.remove(sess.uniqueId)
        return xml(200, null, mapOf("paired" to if (paired) "1" else "0"))
    }
}
