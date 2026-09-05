package np.paila.wallet.transport

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle
import kotlinx.coroutines.*
import np.paila.wallet.core.Protocol
import java.io.ByteArrayOutputStream

/** Paila-specific two-tap NFC exchange; NOT EMV, not bank-card emulation. */
object NfcBus {
    @Volatile var receiveCode: String? = null
    @Volatile var accept: ((String) -> String)? = null
    fun clear() { receiveCode = null; accept = null }
}
class PailaHceService : HostApduService() {
    private var selected = false
    private var expected = 0
    private var incoming = ByteArrayOutputStream()
    @Volatile private var ack = byteArrayOf()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    companion object {
        val AID = byteArrayOf(0xF0.toByte(), 0x50, 0x41, 0x49, 0x4C, 0x41, 0x01)
        val OK = byteArrayOf(0x90.toByte(), 0)
        val ERROR = byteArrayOf(0x69, 0x85.toByte())
        fun bytes(size: Int) = byteArrayOf((size shr 8).toByte(), size.toByte())
    }
    override fun processCommandApdu(command: ByteArray?, extras: Bundle?): ByteArray? {
        val c = command ?: return ERROR
        if (c.size < 5 || NfcBus.receiveCode == null || NfcBus.accept == null) return ERROR
        try {
            if (c[0] == 0.toByte() && c[1] == 0xA4.toByte()) {
                selected = c.size >= 12 && (c[4].toInt() and 255) == AID.size && c.copyOfRange(5, 12).contentEquals(AID)
                return if (selected) OK else ERROR
            }
            if (!selected || c[0] != 0x80.toByte()) return ERROR
            val offset = ((c[2].toInt() and 255) shl 8) or (c[3].toInt() and 255)
            val code = NfcBus.receiveCode!!.toByteArray(Charsets.UTF_8)
            return when(c[1].toInt() and 255) {
                1 -> bytes(code.size) + OK
                2 -> code.copyOfRange(offset.coerceAtMost(code.size), (offset + 200).coerceAtMost(code.size)) + OK
                3 -> { require(c.size == 7 && c[4] == 2.toByte()); expected = ((c[5].toInt() and 255) shl 8) or (c[6].toInt() and 255); require(expected in 1..23000); incoming.reset(); ack = byteArrayOf(); OK }
                4 -> { val len = c[4].toInt() and 255; require(c.size == len + 5 && len in 1..200 && incoming.size() == offset && incoming.size() + len <= expected); incoming.write(c, 5, len); OK }
                5 -> {
                    require(expected > 0 && incoming.size() == expected)
                    val packet = String(incoming.toByteArray(), Charsets.UTF_8); expected = 0; incoming.reset()
                    scope.launch { try { ack = requireNotNull(NfcBus.accept).invoke(packet).toByteArray(Charsets.UTF_8); sendResponseApdu(OK) } catch (_: Exception) { ack = byteArrayOf(); sendResponseApdu(ERROR) } }
                    null
                }
                6 -> bytes(ack.size) + OK
                7 -> ack.copyOfRange(offset.coerceAtMost(ack.size), (offset + 200).coerceAtMost(ack.size)) + OK
                else -> ERROR
            }
        } catch (_: Exception) { return ERROR }
    }
    override fun onDeactivated(reason: Int) { selected = false; expected = 0; incoming.reset(); ack = byteArrayOf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
class NfcReader(private val activity: Activity, private val scope: CoroutineScope) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    @Volatile private var reading = false
    fun start(packet: String? = null, onReceive: (String, String?) -> Unit, onError: (Throwable) -> Unit) {
        require(adapter != null && adapter.isEnabled) { "NFC is unavailable or switched off" }
        adapter.enableReaderMode(activity, { tag ->
            if (!reading) { reading = true; scope.launch(Dispatchers.IO) {
                try {
                    val iso = IsoDep.get(tag) ?: error("Hold the phones' NFC areas together; this is not a Paila receiver")
                    iso.use {
                        it.connect(); it.timeout = 8000
                        fun command(ins: Int, offset: Int = 0, bytes: ByteArray = byteArrayOf()): ByteArray = byteArrayOf(0x80.toByte(), ins.toByte(), (offset shr 8).toByte(), offset.toByte(), bytes.size.toByte()) + bytes
                        fun exchange(c: ByteArray): ByteArray { val r = it.transceive(c); require(r.size >= 2 && r.takeLast(2).toByteArray().contentEquals(PailaHceService.OK)) { "NFC exchange stopped. Keep the receiver open and retry the same payment." }; return r.copyOfRange(0, r.size - 2) }
                        fun read(lenIns: Int, dataIns: Int): String { val l = exchange(command(lenIns)); require(l.size == 2); val size = ((l[0].toInt() and 255) shl 8) or (l[1].toInt() and 255); require(size in 1..23000); val out = ByteArrayOutputStream(); while (out.size() < size) { val part = exchange(command(dataIns, out.size())); require(part.isNotEmpty() && out.size() + part.size <= size); out.write(part) }; return String(out.toByteArray(), Charsets.UTF_8) }
                        exchange(byteArrayOf(0, 0xA4.toByte(), 4, 0, 7) + PailaHceService.AID + byteArrayOf(0))
                        val receive = read(1, 2); val recipient = Protocol.readReceive(receive)
                        var ack: String? = null
                        if (packet != null) {
                            require(Protocol.peek(packet).getString("to") == recipient.getString("walletId")) { "Different recipient. Payment was not sent." }
                            val bytes = packet.toByteArray(Charsets.UTF_8); exchange(command(3, bytes = PailaHceService.bytes(bytes.size)))
                            var offset = 0; while (offset < bytes.size) { val part = bytes.copyOfRange(offset, (offset + 200).coerceAtMost(bytes.size)); exchange(command(4, offset, part)); offset += part.size }
                            exchange(command(5)); ack = read(6, 7)
                        }
                        withContext(Dispatchers.Main) { onReceive(receive, ack) }
                    }
                } catch (e: Exception) { withContext(Dispatchers.Main) { onError(e) } } finally { reading = false }
            } }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }
    fun stop() { adapter?.disableReaderMode(activity) }
}
