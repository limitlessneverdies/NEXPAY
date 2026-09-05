package np.nexpay.wallet.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray().also { put(key, it) }

data class Note(val id: String, val amount: Long, val expires: Long, val spent: Boolean, val status: String)
data class Receipt(val id: String, val amount: Long, val status: String, val error: String, val packet: String, val to: String = "")
data class Entry(val id: String, val amount: Long, val name: String, val kind: String, val created: Long)
data class WalletState(
    val ready: Boolean = false, val name: String = "", val walletId: String = "", val balance: Long = 0,
    val reserved: Long = 0, val total: Long = 0, val pending: Long = 0, val spent: Long = 0,
    val server: String = "", val issuer: String = "", val connected: Boolean = false, val busy: Boolean = false,
    val error: String = "", val message: String = "", val notes: List<Note> = emptyList(), val incoming: List<Receipt> = emptyList(),
    val outgoing: List<Receipt> = emptyList(), val activity: List<Entry> = emptyList(), val queued: Int = 0, val nextTopup: Long = 0,
    val failed: List<String> = emptyList()
)

class WalletRepository(context: Context) {
    private val store = SecureStore(context.applicationContext)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(WalletState())
    val state = mutable.asStateFlow()
    init { publish() }
    private fun publish(connected: Boolean = mutable.value.connected, busy: Boolean = mutable.value.busy, error: String = mutable.value.error, message: String = mutable.value.message) {
        val root = store.read(); val s = root.optJSONObject("serverState") ?: JSONObject()
        val sent = root.array("outgoing").objects()
        mutable.value = WalletState(
            ready = s.has("walletId"), name = s.optString("name"), walletId = s.optString("walletId"), balance = s.optLong("balanceMinor"),
            reserved = s.optLong("reservedMinor"), total = s.optLong("totalMinor"),
            pending = root.array("incoming").objects().filter { it.getString("status") == "pending" }.sumOf { it.getLong("amount") },
            spent = root.array("outgoing").objects().filter { it.getString("status") == "prepared" || it.getString("status") == "delivered" }.sumOf { it.getLong("amount") },
            server = root.optString("server"), issuer = root.optJSONObject("config")?.optString("issuerFingerprint") ?: "", connected = connected,
            busy = busy, error = error, message = message,
            notes = (s.optJSONArray("vouchers") ?: JSONArray()).objects().map { n -> Note(n.getString("id"), n.getLong("amount"), n.getLong("expires"), sent.any { it.getString("id") == n.getString("id") }, n.getString("status")) },
            incoming = root.array("incoming").objects().map { Receipt(it.getString("id"), it.getLong("amount"), it.getString("status"), it.optString("error"), it.getString("packet")) },
            outgoing = sent.map { Receipt(it.getString("id"), it.getLong("amount"), it.getString("status"), it.optString("error"), it.getString("packet"), it.getString("name")) },
            activity = (s.optJSONArray("activity") ?: JSONArray()).objects().map { Entry(it.getString("id"), it.getLong("amountMinor"), it.getString("peerName"), it.getString("kind"), it.getLong("created")) },
            queued = root.array("queue").objects().count { it.optString("status") == "queued" }, nextTopup = s.optLong("nextTopupAt"),
            failed = root.array("queue").objects().filter { it.optString("status") == "failed" }.map { it.optString("error", "Request failed") }
        )
    }
    fun clearMessage() = publish(error = "", message = "")
    fun showError(e: Throwable) = publish(busy = false, error = friendlyNet(e))
    private fun friendlyNet(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.net.UnknownHostException || t is java.net.ConnectException || t is java.net.SocketTimeoutException || t is javax.net.ssl.SSLException) return "You're offline. Everything is saved and will sync when you reconnect."
            val m = (t.message ?: "").lowercase()
            if (m.contains("unable to resolve host") || m.contains("no address associated") || m.contains("network is unreachable") || m.contains("connection refused") || m.contains("timed out") || m.contains("econnrefused") || m.contains("enotfound") || m.contains("software caused connection abort") || m.contains("connection reset") || m.contains("broken pipe")) return "You're offline. Everything is saved and will sync when you reconnect."
            t = t.cause
        }
        return e.message ?: "Something went wrong. Retry safely."
    }
    suspend fun create(server: String, name: String) = withContext(Dispatchers.IO) { mutex.withLock {
        publish(busy = true, error = "")
        try {
            val url = Api.serverUrl(server); val displayName = Protocol.safeName(name)
            val config = Api.call(url, "/v1/config")
            require(config.getString("mode") == "test" && config.getInt("protocol") == 1) { "This app only connects to the NexPay network" }
            Protocol.publicKey(config.getString("issuerPublicKey"))
            require(config.getString("issuerFingerprint") == Protocol.sha(Protocol.unb64(config.getString("issuerPublicKey")))) { "Invalid server identity" }
            val old = store.read(); old.optJSONObject("config")?.let { require(it.getString("issuerPublicKey") == config.getString("issuerPublicKey")) { "This device already belongs to another issuer. Do not replace the wallet." } }
            store.update { it.put("server", url).put("config", config) }
            val request = Protocol.obj("v" to 1, "publicKey" to store.publicKey, "name" to displayName, "opId" to Protocol.newId(), "ts" to System.currentTimeMillis())
            val s = Api.call(url, "/v1/wallets", store.sign("register", request))
            require(s.getString("walletId") == Protocol.walletId(store.publicKey)) { "Server returned a different wallet" }
            store.update { it.put("serverState", s) }; publish(connected = true, busy = false, message = "Welcome to NexPay. Rs 5,000 ready.")
        } catch (e: Exception) { publish(connected = false, busy = false, error = friendlyNet(e)) }
    } }
    private fun call(root: JSONObject, op: String, data: JSONObject, opId: String): JSONObject {
        val req = Protocol.obj("v" to 1, "walletId" to Protocol.walletId(store.publicKey), "op" to op, "opId" to opId, "ts" to System.currentTimeMillis(), "data" to data)
        return Api.call(root.getString("server"), "/v1/actions", store.sign("request", req))
    }
    suspend fun enqueue(op: String, data: JSONObject) = withContext(Dispatchers.IO) {
        require(state.value.ready) { "Set up your wallet first" }
        require(state.value.queued == 0) { "Resolve the queued request before starting another payment" }
        val job = Protocol.obj("id" to Protocol.newId(), "op" to op, "data" to data, "status" to "queued")
        store.update {
            require(it.array("queue").objects().none { j -> j.optString("status") == "queued" }) { "Resolve your queued request first" }
            it.array("queue").put(job)
        }
        publish(message = "Request saved. Connecting to the server…")
        sync()
    }
    suspend fun sync() = withContext(Dispatchers.IO) { mutex.withLock {
        val root = store.read(); if (!root.has("serverState")) return@withLock
        publish(busy = true, error = "")
        try {
            val config = Api.call(root.getString("server"), "/v1/config")
            require(config.getString("issuerPublicKey") == root.getJSONObject("config").getString("issuerPublicKey") && config.getString("mode") == "test") { "Server identity changed. Payments stopped." }
            var message = "Wallet is up to date."
            for (job in root.array("queue").objects().filter { it.optString("status") == "queued" }) {
                try {
                    call(root, job.getString("op"), job.getJSONObject("data"), job.getString("id"))
                    store.update { it.put("queue", JSONArray(it.array("queue").objects().filter { j -> j.getString("id") != job.getString("id") })) }
                    message = when (job.getString("op")) { "pay" -> "Payment settled."; "topup" -> "Rs 5,000 added."; "reserve" -> "Offline note is ready."; "reclaim" -> "Expired note refunded."; else -> "Done." }
                } catch (e: ApiError) {
                    if (e.status in listOf(400, 403, 404, 409) || e.code == "TOPUP_LIMIT") {
                        store.update { it.array("queue").objects().find { j -> j.getString("id") == job.getString("id") }?.put("status", "failed")?.put("error", e.message) }
                        message = "A request was rejected. See Settings for details."
                    } else throw e
                }
            }
            for (receipt in store.read().array("incoming").objects().filter { it.getString("status") == "pending" }) {
                try {
                    call(root, "redeem", Protocol.obj("payment" to receipt.getString("packet")), receipt.getString("opId"))
                    store.update { it.array("incoming").objects().find { r -> r.getString("id") == receipt.getString("id") }?.put("status", "settled") }
                } catch (e: ApiError) {
                    if (e.status in listOf(400, 403, 404, 409)) store.update { it.array("incoming").objects().find { r -> r.getString("id") == receipt.getString("id") }?.put("status", "rejected")?.put("error", e.message) } else throw e
                }
            }
            val s = call(root, "state", JSONObject(), Protocol.newId())
            require(s.getString("walletId") == Protocol.walletId(store.publicKey)) { "Wallet identity mismatch" }
            store.update { current ->
                current.put("serverState", s)
                current.array("outgoing").objects().forEach { out ->
                    val n = s.array("vouchers").objects().find { it.getString("id") == out.getString("id") }
                    if (n?.optString("status") == "redeemed") out.put("status", if (n.optString("recipient") == out.optString("to")) "settled" else "conflict")
                    if (n?.optString("status") == "reclaimed") out.put("status", "refunded")
                }
            }
            publish(connected = true, busy = false, message = message)
        } catch (e: Exception) { publish(connected = false, busy = false, error = friendlyNet(e) + " Saved requests are safe to retry.") }
    } }
    fun receiveCode(): String {
        check(state.value.ready) { "Set up online first" }; val now = System.currentTimeMillis()
        return store.sign("receive", Protocol.obj("v" to 1, "walletId" to state.value.walletId, "publicKey" to store.publicKey, "name" to state.value.name, "requestId" to Protocol.newId(), "createdAt" to now, "expiresAt" to now + Protocol.NOTE_LIFE))
    }
    fun makeOffline(receiveRaw: String, amount: Long, method: String = "QR"): String {
        val r = Protocol.readReceive(receiveRaw); require(r.getString("walletId") != state.value.walletId) { "Choose another wallet" }
        var packet = ""
        store.update { root ->
            require(root.array("queue").objects().none { it.optString("status") == "queued" }) { "Sync your queued request before sending offline" }
            val outgoing = root.array("outgoing").objects()
            val myKey = store.publicKey
            val own = root.getJSONObject("serverState").array("vouchers").objects().filter { it.getLong("amount") >= amount && it.getString("status") == "reserved" && it.getLong("expires") > System.currentTimeMillis() && outgoing.none { o -> o.getString("id") == it.getString("id") } }.minByOrNull { it.getLong("amount") }
            if (own != null) {
                Protocol.verify("voucher", own.getString("certificate"), root.getJSONObject("config").getString("issuerPublicKey"))
                val d = Protocol.obj("v" to 1, "voucher" to own.getString("certificate"), "fromKey" to myKey, "to" to r.getString("walletId"), "requestId" to r.getString("requestId"), "createdAt" to System.currentTimeMillis(), "amountMinor" to amount, "hop" to 0)
                packet = store.sign("payment", d)
                root.array("outgoing").put(Protocol.obj("id" to own.getString("id"), "hash" to Protocol.sha(Protocol.canonical(Protocol.peek(packet)).toByteArray(Charsets.UTF_8)), "parent" to JSONObject.NULL, "packet" to packet, "amount" to amount, "to" to r.getString("walletId"), "name" to r.getString("name"), "status" to "prepared"))
            } else {
                val cands = root.array("incoming").objects().filter { it.getString("status") == "pending" || it.getString("status") == "settled" }.mapNotNull { inc ->
                    try {
                        val p = Protocol.peek(inc.getString("packet")); val ph = p.optInt("hop", 0)
                        val spent = outgoing.filter { o -> o.optString("parent") == inc.getString("id") }.sumOf { it.getLong("amount") }
                        if (inc.getLong("amount") - spent >= amount) Triple(inc, ph, inc.getLong("amount") - spent) else null
                    } catch (_: Exception) { null }
                }.sortedBy { it.third }
                val best = cands.firstOrNull() ?: error("No reserved balance covers this amount. Reserve more while online.")
                val newHop = best.second + 1
                require(newHop <= Protocol.MAX_HOPS) { "Transfer chain is too long" }
                require(!(method == "QR" && newHop > Protocol.QR_HOPS)) { "Chain too long for QR. Use Bluetooth." }
                val parentData = Protocol.peek(best.first.getString("packet"))
                val parentChain = (0 until (parentData.optJSONArray("chain")?.length() ?: 0)).map { parentData.getJSONArray("chain").getString(it) }
                val parentHash = Protocol.sha(Protocol.canonical(parentData).toByteArray(Charsets.UTF_8))
                val d = Protocol.obj("v" to 1, "fromKey" to myKey, "to" to r.getString("walletId"), "requestId" to r.getString("requestId"), "createdAt" to System.currentTimeMillis(), "amountMinor" to amount, "hop" to newHop, "prev" to parentHash, "chain" to org.json.JSONArray((parentChain + best.first.getString("packet")).toList()))
                packet = store.sign("payment", d)
                root.array("outgoing").put(Protocol.obj("id" to Protocol.sha(Protocol.canonical(Protocol.peek(packet)).toByteArray(Charsets.UTF_8)), "hash" to Protocol.sha(Protocol.canonical(Protocol.peek(packet)).toByteArray(Charsets.UTF_8)), "parent" to best.first.getString("id"), "packet" to packet, "amount" to amount, "to" to r.getString("walletId"), "name" to r.getString("name"), "status" to "prepared"))
            }
        }
        // Persist first: transmission failure must never make the note spendable again.
        publish(message = "Payment locked to this recipient. Share or retry the same message.")
        return packet
    }
    fun acceptOffline(packet: String): String {
        val root = store.read(); val issuer = root.getJSONObject("config").getString("issuerPublicKey")
        val p = Protocol.readPayment(packet, issuer, state.value.walletId)
        store.update { current ->
            val old = current.array("incoming").objects().find { it.getString("id") == p.hash }
            if (old == null) current.array("incoming").put(Protocol.obj("id" to p.hash, "hash" to p.hash, "packet" to packet, "amount" to p.amount, "status" to "pending", "opId" to Protocol.newId()))
        }
        publish(message = "Received. Pending server settlement; not spendable yet.")
        return store.sign("ack", Protocol.obj("v" to 1, "paymentHash" to p.hash, "walletId" to state.value.walletId, "status" to "received_pending"))
    }
    fun verifyAck(ack: String, packet: String, receiveRaw: String) {
        val r = Protocol.readReceive(receiveRaw)
        val a = Protocol.verify("ack", ack, r.getString("publicKey"))
        val expected = Protocol.sha(Protocol.canonical(Protocol.peek(packet)).toByteArray(Charsets.UTF_8))
        require(a.getString("paymentHash") == expected && a.getString("walletId") == r.getString("walletId")) { "Recipient acknowledgement mismatch" }
        store.update { it.array("outgoing").objects().find { o -> o.optString("hash") == expected || o.getString("id") == expected }?.put("status", "delivered") }
        publish(message = "Delivered to recipient. Settlement is still pending.")
    }
    fun clearFailed() { store.update { it.put("queue", JSONArray(it.array("queue").objects().filter { j -> j.optString("status") != "failed" })) }; publish() }
    suspend fun changeEndpoint(url: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val normalized = Api.serverUrl(url); val config = Api.call(normalized, "/v1/config")
        require(config.getString("issuerPublicKey") == store.read().getJSONObject("config").getString("issuerPublicKey")) { "Different server identity. Migration refused." }
        store.update { it.put("server", normalized) }; publish(message = "Server address updated; issuer identity is unchanged.")
    } }
}
