package np.paila.wallet.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

object Protocol {
    const val OFFLINE_LIMIT = 50_000L
    const val NOTE_LIFE = 86_400_000L
    const val REDEEM_GRACE = 604_800_000L
    const val SKEW = 300_000L
    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(s: String): ByteArray {
        require(s.isNotEmpty() && s.length <= 24000 && s.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid payment encoding" }
        return Base64.getUrlDecoder().decode(s).also { require(b64(it) == s) { "Non-canonical encoding" } }
    }
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun walletId(pub: String) = "pa_" + sha(unb64(pub)).take(32)
    fun publicKey(pub: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(pub))).also {
        val ec = it as java.security.interfaces.ECPublicKey
        val spec = AlgorithmParameters.getInstance("EC").apply { init(java.security.spec.ECGenParameterSpec("secp256r1")) }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        require(ec.params.order == spec.order && ec.params.curve == spec.curve && ec.params.generator == spec.generator && ec.params.cofactor == spec.cofactor) { "Use a P-256 key" }
        require(b64(it.encoded) == pub) { "Non-canonical public key" }
    }
    fun sign(kind: String, data: JSONObject, key: PrivateKey): String {
        val encoded = b64(data.toString().toByteArray(Charsets.UTF_8))
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(key); update("paila:$kind:v1:$encoded".toByteArray(Charsets.UTF_8)); sign() }
        return "p1.$encoded.${b64(sig)}"
    }
    fun peek(raw: String): JSONObject {
        require(raw.length <= 23000) { "Payment message too large" }
        val pieces = raw.split('.')
        require(pieces.size == 3 && pieces[0] == "p1") { "Not a Paila payment code" }
        return JSONObject(String(unb64(pieces[1]), Charsets.UTF_8)).also { require(it.getInt("v") == 1) { "Unsupported payment version" } }
    }
    fun verify(kind: String, raw: String, pub: String): JSONObject {
        val data = peek(raw)
        val pieces = raw.split('.')
        val valid = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(pub)); update("paila:$kind:v1:${pieces[1]}".toByteArray(Charsets.UTF_8)); verify(unb64(pieces[2]))
        }
        require(valid) { "Payment signature is invalid" }
        return data
    }
    fun readReceive(raw: String, now: Long = System.currentTimeMillis()): JSONObject {
        val d = peek(raw)
        val r = verify("receive", raw, d.getString("publicKey"))
        require(walletId(r.getString("publicKey")) == r.getString("walletId")) { "Recipient key mismatch" }
        safeName(r.getString("name"))
        val start = r.getLong("createdAt"); val end = r.getLong("expiresAt")
        require(start <= now + SKEW && end > now && end - start in 1..NOTE_LIFE) { "Receive code expired. Ask for a new one." }
        require(r.getString("requestId").matches(Regex("[A-Za-z0-9_-]{16,80}"))) { "Invalid receive request" }
        return r
    }
    data class Payment(val note: JSONObject, val data: JSONObject, val hash: String)
    fun readPayment(raw: String, issuer: String, recipient: String, now: Long = System.currentTimeMillis()): Payment {
        val data = peek(raw)
        val note = verify("voucher", data.getString("voucher"), issuer)
        require(note.getString("issuer") == sha(unb64(issuer))) { "Payment belongs to a different server" }
        require(note.getLong("amount") in 1..OFFLINE_LIMIT) { "Offline amount exceeds limit" }
        require(note.getString("owner") == walletId(note.getString("ownerKey"))) { "Note owner mismatch" }
        verify("payment", raw, note.getString("ownerKey"))
        require(data.getString("to") == recipient && recipient != note.getString("owner")) { "Payment is addressed to a different wallet" }
        val created = note.getLong("createdAt"); val expires = note.getLong("expiresAt"); val time = data.getLong("createdAt")
        require(expires - created == NOTE_LIFE && now < expires && time in (created - SKEW)..expires && time <= now + SKEW) { "Offline note expired or invalid device clock" }
        require(note.getString("id").matches(Regex("[A-Za-z0-9_-]{16,80}")) && data.getString("requestId").matches(Regex("[A-Za-z0-9_-]{16,80}"))) { "Invalid payment identifier" }
        return Payment(note, data, sha(canonical(data).toByteArray(Charsets.UTF_8)))
    }
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> error("Unsupported value")
    }
    fun paisa(input: String, max: Long = 10_000_000): Long {
        require(input.trim().matches(Regex("[0-9]{1,8}(\\.[0-9]{1,2})?"))) { "Enter a positive rupee amount with up to two decimals" }
        val result = BigDecimal(input.trim()).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        require(result in 1..max) { "Amount is outside the allowed limit" }
        return result
    }
    fun safeName(name: String): String = name.trim().also {
        require(it.length in 1..48 && !Regex("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]").containsMatchIn(it)) { "Enter a name (1–48 characters, no control characters)" }
    }
    fun newId() = UUID.randomUUID().toString()
    fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }
}
