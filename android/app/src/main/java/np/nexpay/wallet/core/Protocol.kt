package np.nexpay.wallet.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

object Protocol {
    const val OFFLINE_LIMIT = 500_000L
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
        require(pieces.size == 3 && pieces[0] == "p1") { "Not a NexPay payment code" }
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
    const val MAX_HOPS = 3
    const val QR_HOPS = 1
    data class Payment(val note: JSONObject, val data: JSONObject, val hash: String, val amount: Long, val hop: Int)
    data class ChainLink(val sender: String, val to: String, val amount: Long, val hash: String)

    fun chainDepth(packet: String): Int = try { peek(packet).optInt("hop", 0) } catch (_: Exception) { 0 }

    fun readPayment(raw: String, issuer: String, recipient: String, now: Long = System.currentTimeMillis(), settlement: Boolean = false): Payment {
        val data = peek(raw)
        val hop = data.optInt("hop", 0)
        require(hop in 0..MAX_HOPS) { "Transfer chain is too long or invalid" }
        val chainArr = data.optJSONArray("chain")
        val links = (0 until (chainArr?.length() ?: 0)).map { chainArr!!.getString(it) }
        require(links.size == hop) { "Transfer chain does not match hop count" }
        val note = verify("voucher", if (hop == 0) data.getString("voucher") else peek(links[0]).getString("voucher"), issuer)
        require(note.getString("issuer") == sha(unb64(issuer))) { "Payment belongs to a different server" }
        require(note.getLong("amount") in 1..OFFLINE_LIMIT) { "Offline amount exceeds limit" }
        require(note.getString("owner") == walletId(note.getString("ownerKey"))) { "Note owner mismatch" }
        require(note.getString("id").matches(Regex("[A-Za-z0-9_-]{16,80}"))) { "Invalid note identifier" }
        val ncreated = note.getLong("createdAt"); val nexpires = note.getLong("expiresAt")
        require(nexpires - ncreated == NOTE_LIFE) { "Invalid note lifetime" }
        var parentAmount = note.getLong("amount"); var parentHash = ""; var parentTo = ""
        for (i in 0 until hop) {
            val ld = peek(links[i])
            require(ld.optInt("hop", 0) == i) { "Broken chain continuity" }
            val lf = if (ld.has("fromKey")) ld.getString("fromKey") else if (i == 0) note.getString("ownerKey") else error("Chain link is missing its sender key")
            publicKey(lf)
            require(ld.getString("requestId").matches(Regex("[A-Za-z0-9_-]{16,80}"))) { "Invalid chain request" }
            if (i == 0) require(walletId(lf) == note.getString("owner")) { "Only the note owner can start a transfer" }
            else { require(walletId(lf) == parentTo) { "Chain sender does not follow the previous hop" }; require(ld.getString("prev") == parentHash) { "Chain link does not reference the previous transfer" } }
            verify("payment", links[i], lf)
            val lto = ld.getString("to"); require(lto != walletId(lf)) { "Choose another wallet" }
            val lamt = ld.getLong("amountMinor"); require(lamt in 1..parentAmount) { "Chain amount exceeds what was received" }
            val lct = ld.getLong("createdAt"); require(lct in (ncreated - SKEW)..nexpires) { "Chain transfer outside note lifetime" }
            val lh = sha(canonical(ld).toByteArray(Charsets.UTF_8))
            parentHash = lh; parentAmount = lamt; parentTo = lto
        }
        val senderKey = if (data.has("fromKey")) data.getString("fromKey") else note.getString("ownerKey")
        publicKey(senderKey)
        if (hop == 0) require(walletId(senderKey) == note.getString("owner")) { "Only the note owner can start a transfer" }
        else { require(walletId(senderKey) == parentTo) { "Transfer sender does not follow the chain" }; require(data.getString("prev") == parentHash) { "Transfer does not reference the previous transfer" } }
        verify("payment", raw, senderKey)
        require(data.getString("to") == recipient) { "Payment is addressed to a different wallet" }
        if (hop == 0) require(recipient != note.getString("owner")) { "Choose another wallet" } else require(recipient != walletId(senderKey)) { "Choose another wallet" }
        val paid = if (data.has("amountMinor")) data.getLong("amountMinor") else parentAmount
        require(paid in 1..parentAmount) { "Payment exceeds the available value" }
        val time = data.getLong("createdAt")
        val earliest = if (hop == 0) ncreated else ncreated
        require(time in (earliest - SKEW)..nexpires && time <= now + SKEW) { "Offline note expired or invalid device clock" }
        require(now < nexpires + if (settlement) REDEEM_GRACE else 0) { "Offline note expired" }
        require(data.getString("requestId").matches(Regex("[A-Za-z0-9_-]{16,80}"))) { "Invalid payment identifier" }
        return Payment(note, data, sha(canonical(data).toByteArray(Charsets.UTF_8)), paid, hop)
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
