package np.nexpay.wallet.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.net.wifi.p2p.*
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import np.nexpay.wallet.core.Protocol
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Radio code is source-complete but needs two physical Android devices for validation. */
class WireSession(private val input: InputStream, private val output: OutputStream, private val closer: () -> Unit) : Closeable {
    fun read(): String {
        val d = DataInputStream(input); val size = d.readInt()
        require(size in 1..23000) { "Invalid nearby message size" }
        val bytes = ByteArray(size); d.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
    fun write(text: String) { val bytes = text.toByteArray(Charsets.UTF_8); require(bytes.size in 1..23000); DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() } }
    override fun close() = closer()
}
data class Peer(val name: String, val address: String)

@SuppressLint("MissingPermission")
class Nearby(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onPeers: (List<Peer>) -> Unit,
    private val onRecipient: (String) -> Unit,
    private val acceptPayment: (String) -> String
) : Closeable {
    companion object { val UUID_SERVICE: UUID = UUID.fromString("0c658bf0-1de7-44c6-8a4e-2a9d5418d021"); const val WIFI_PORT = 47872 }
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val wifi = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = wifi?.initialize(context, context.mainLooper) { onError(IllegalStateException("Wi-Fi Direct channel disconnected. Reopen Nearby.")) }
    private val closeables = CopyOnWriteArrayList<Closeable>()
    private var session: WireSession? = null
    private var bluetoothServer: BluetoothServerSocket? = null
    private var wifiServer: ServerSocket? = null
    private var pendingBluetooth: BluetoothSocket? = null
    private var mode = ""
    private var receiveCode = ""
    private var peers = linkedMapOf<String, Peer>()
    private var wifiConnecting = false
    private var registered = false
    private var generation = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> if (mode == "bt-send") {
                        @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let { peers[it.address] = Peer(it.name ?: "Bluetooth device", it.address); onPeers(peers.values.toList()) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (mode == "bt-send") onStatus(if (peers.isEmpty()) "No devices found. Open Receive → Bluetooth on the other phone, then retry." else "Choose the receiver. Android may ask you to pair.")
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> if (mode == "wifi-send") wifi?.requestPeers(channel) { list -> onPeers(list.deviceList.map { Peer(it.deviceName ?: "Wi-Fi Direct device", it.deviceAddress) }) }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> if (mode.startsWith("wifi")) wifi?.requestConnectionInfo(channel) { info -> handleWifiInfo(info) }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> if (mode.startsWith("wifi") && intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) != WifiP2pManager.WIFI_P2P_STATE_ENABLED) onError(IllegalStateException("Turn on Wi-Fi and retry."))
                }
            } catch (e: Exception) { onError(e) }
        }
    }
    init {
        val filter = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION); addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION); addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED); registered = true
    }
    private fun io(block: suspend () -> Unit) { val gen = generation; scope.launch(Dispatchers.IO) { try { block() } catch (e: Exception) { if (gen == generation) withContext(Dispatchers.Main) { onError(e) } } } }
    private suspend fun status(s: String) = withContext(Dispatchers.Main) { onStatus(s) }
    private fun guard(session: WireSession) { scope.launch { delay(90_000); runCatching { session.close() } } }
    private fun sender(s: WireSession) { closeables.add(s); guard(s); io { val raw = s.read(); Protocol.readReceive(raw); session = s; withContext(Dispatchers.Main) { onRecipient(raw); onStatus("Recipient connected. Check the name and wallet ID, then confirm payment.") } } }
    private fun receiver(s: WireSession, raw: String) { closeables.add(s); guard(s); io {
        s.use { it.write(raw); val packet = it.read(); val ack = acceptPayment(packet); it.write(ack) }; status("Payment saved on this phone. Pending server settlement.")
    } }
    fun startBluetoothReceive(code: String) {
        stopTransfer(); mode = "bt-receive"; require(adapter != null && adapter.isEnabled) { "Enable Bluetooth first" }; receiveCode = code
        bluetoothServer = adapter.listenUsingRfcommWithServiceRecord("NexPay", UUID_SERVICE)
        onStatus("Listening. Keep this screen open and make this phone discoverable.")
        io { val socket = bluetoothServer!!.accept(90_000); val s = WireSession(socket.inputStream, socket.outputStream) { socket.close() }; receiver(s, code); bluetoothServer?.close(); bluetoothServer = null }
    }
    fun discoverBluetooth() {
        stopTransfer(); mode = "bt-send"; require(adapter != null && adapter.isEnabled) { "Enable Bluetooth first" }
        peers.clear(); adapter.bondedDevices.forEach { peers[it.address] = Peer(it.name ?: "Paired device", it.address) }; onPeers(peers.values.toList())
        adapter.cancelDiscovery(); require(adapter.startDiscovery()) { "Bluetooth discovery did not start. Check Nearby permissions and Bluetooth settings." }; onStatus("Looking for the receiver…")
    }
    fun connectBluetooth(address: String) {
        adapter?.cancelDiscovery(); onStatus("Connecting. Accept Android's pairing prompt on both phones.")
        io { val socket = requireNotNull(adapter).getRemoteDevice(address).createRfcommSocketToServiceRecord(UUID_SERVICE); pendingBluetooth = socket
            val timeout = scope.launch { delay(30_000); if (pendingBluetooth === socket) runCatching { socket.close() } }
            try { socket.connect(); timeout.cancel(); pendingBluetooth = null; sender(WireSession(socket.inputStream, socket.outputStream) { socket.close() }) } catch (e: Exception) { socket.close(); throw e }
        }
    }
    private fun action(success: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = success()
        override fun onFailure(reason: Int) { onError(IllegalStateException(when(reason) { WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this phone"; WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Disconnect the old group and retry."; else -> "Wi-Fi Direct failed. Check Wi-Fi, Nearby permission and Location mode." })) }
    }
    fun startWifiReceive(code: String) {
        stopTransfer(); mode = "wifi-receive"; receiveCode = code
        require(wifi != null && channel != null) { "Wi-Fi Direct unavailable" }
        wifi.createGroup(channel, action { onStatus("Wi-Fi Direct group created. Choose this phone on the sender."); wifi.requestConnectionInfo(channel) { handleWifiInfo(it) } })
    }
    fun discoverWifi() {
        stopTransfer(); mode = "wifi-send"; require(wifi != null && channel != null) { "Wi-Fi Direct unavailable" }
        wifi.discoverPeers(channel, action { onStatus("Looking for Wi-Fi Direct devices. Keep Location mode on if your phone requires it."); wifi.requestPeers(channel) { onPeers(it.deviceList.map { d -> Peer(d.deviceName ?: "Nearby phone", d.deviceAddress) }) } })
    }
    fun connectWifi(address: String) {
        require(wifi != null && channel != null); wifi.connect(channel, WifiP2pConfig().apply { deviceAddress = address; groupOwnerIntent = 0 }, action { onStatus("Waiting for the receiver to accept the Wi-Fi Direct connection…") })
    }
    private fun handleWifiInfo(info: WifiP2pInfo) {
        if (!info.groupFormed || wifiConnecting) return
        if (mode == "wifi-receive" && info.isGroupOwner) {
            wifiConnecting = true
            io { val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(WIFI_PORT)); soTimeout = 90_000 }; wifiServer = server; val socket = server.accept(); socket.soTimeout = 90_000; server.close(); wifiServer = null; receiver(WireSession(socket.getInputStream(), socket.getOutputStream()) { socket.close() }, receiveCode) }
        } else if (mode == "wifi-send" && !info.isGroupOwner) {
            wifiConnecting = true
            io {
                var result: Socket? = null
                repeat(8) { if (result == null) { val socket = Socket(); try { socket.connect(InetSocketAddress(info.groupOwnerAddress, WIFI_PORT), 2000); socket.soTimeout = 90_000; result = socket } catch (_: Exception) { socket.close(); delay(500) } } }
                val s = result ?: error("Receiver is not listening. Open Receive → Wi-Fi Direct on the other phone.")
                sender(WireSession(s.getInputStream(), s.getOutputStream()) { s.close() })
            }
        } else if (mode == "wifi-send") onError(IllegalStateException("Unexpected group owner. Disconnect and let the receiver create a group first."))
    }
    suspend fun deliver(packet: String): String = withContext(Dispatchers.IO) {
        val s = session ?: error("Nearby connection lost. Reconnect or share the saved payment code.")
        s.write(packet); val ack = s.read(); s.close(); session = null; ack
    }
    fun stopTransfer() {
        generation++; mode = ""; wifiConnecting = false; session = null
        closeables.forEach { runCatching { it.close() } }; closeables.clear()
        runCatching { bluetoothServer?.close() }; bluetoothServer = null; runCatching { pendingBluetooth?.close() }; pendingBluetooth = null
        runCatching { wifiServer?.close() }; wifiServer = null; runCatching { adapter?.cancelDiscovery() }
        runCatching { wifi?.cancelConnect(channel, null) }; runCatching { wifi?.removeGroup(channel, null) }
        onPeers(emptyList())
    }
    override fun close() { stopTransfer(); if (registered) { context.unregisterReceiver(receiver); registered = false }; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { channel?.close() } }
}
