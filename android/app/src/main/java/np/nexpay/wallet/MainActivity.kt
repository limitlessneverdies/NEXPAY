package np.nexpay.wallet

import android.Manifest
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import np.nexpay.wallet.core.*
import np.nexpay.wallet.transport.*
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Lime = Color(0xFFDFFF91)
private val Ink = Color(0xFF1B211E)
private val Warm = Color(0xFFEEEDF1)
private data class ReviewRequest(val title: String, val amount: Long, val person: String, val details: String, val action: () -> Unit)
private fun money(value: Long) = "Rs " + DecimalFormat("#,##0.00").format(value / 100.0)
private fun date(value: Long) = SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).format(Date(value))

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private var appearance by mutableStateOf("light")
    private var review by mutableStateOf<ReviewRequest?>(null)
    private val repo get() = (application as NexPayApp).repository
    private var screen by mutableStateOf("Wallet")
    private var method by mutableStateOf("Online")
    private var receiveRaw by mutableStateOf("")
    private var ownReceive by mutableStateOf("")
    private var radioStatus by mutableStateOf("")
    private var peers by mutableStateOf(emptyList<Peer>())
    private var paymentPacket by mutableStateOf("")
    private var codeDialog by mutableStateOf("")
    private var codeTitle by mutableStateOf("")
    private var localError by mutableStateOf("")
    private var scanPurpose = "recipient"
    private var afterPermission: (() -> Unit)? = null
    private var afterBluetooth: (() -> Unit)? = null
    private var afterCredential: (() -> Unit)? = null
    private lateinit var nearby: Nearby
    private lateinit var nfc: NfcReader
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = afterPermission; afterPermission = null
        if (grants.values.all { it }) runSafe { action?.invoke() } else localError = "Permission was denied. You can retry, open App permissions in Settings, or use a pasted QR code."
    }
    private val bluetoothEnable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = afterBluetooth; afterBluetooth = null
        if (getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) runSafe { action?.invoke() } else localError = "Bluetooth is still off. No payment was sent."
    }
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == RESULT_CANCELED) localError = "Discoverability was cancelled. Paired devices can still connect; new devices may not find you." }
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = afterCredential; afterCredential = null
        if (result.resultCode == RESULT_OK) runSafe { action?.invoke() } else localError = "Payment confirmation cancelled. No new payment was created."
    }
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw -> runSafe {
            if (scanPurpose == "payment") { repo.acceptOffline(raw); work { repo.sync() } }
            else { Protocol.readReceive(raw); receiveRaw = raw; screen = "Send"; paymentPacket = "" }
        } }
    }
    private fun runSafe(block: () -> Unit) { try { block() } catch (e: Exception) { localError = e.message ?: "Could not complete this action" } }
    private fun work(block: suspend () -> Unit) { lifecycleScope.launch { try { block() } catch (e: Exception) { localError = e.message ?: "Could not complete this action" } } }
    private fun permissionThen(requested: Array<String>, action: () -> Unit) {
        val missing = requested.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) runSafe(action) else { afterPermission = action; permissions.launch(missing.toTypedArray()) }
    }
    private fun btThen(action: () -> Unit) {
        val p = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionThen(p) {
            val a = getSystemService(BluetoothManager::class.java)?.adapter ?: error("This device has no Bluetooth radio")
            if (a.isEnabled) action() else { afterBluetooth = action; bluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        }
    }
    private fun wifiThen(action: () -> Unit) = permissionThen(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), action)
    private fun authenticate(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) { localError = "Set a phone screen lock in Android Security settings before confirming payments."; return }
        @Suppress("DEPRECATION") val intent = keyguard.createConfirmDeviceCredentialIntent("Confirm NexPay payment", "Use your phone's screen lock.")
        if (intent == null) { localError = "Android could not open device confirmation. No payment was created."; return }
        afterCredential = action; credential.launch(intent)
    }
    private fun scan(purpose: String) { scanPurpose = purpose; permissionThen(arrayOf(Manifest.permission.CAMERA)) { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(if (purpose == "payment") "Scan the sender's payment code" else "Scan the receiver's NexPay code").setBeepEnabled(false).setOrientationLocked(true)) } }
    private fun copy(value: String) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NexPay payment code", value)); radioStatus = "Copied. Share only with your intended recipient." }
    private fun stopRadios() { nearby.stopTransfer(); nfc.stop(); NfcBus.clear(); peers = emptyList(); radioStatus = "" }
    private fun go(destination: String) { stopRadios(); screen = destination; paymentPacket = ""; receiveRaw = ""; if (destination == "Receive") ownReceive = repo.receiveCode() }
    private fun startReceiver(transport: String) = runSafe {
        stopRadios(); method = transport
        if (ownReceive.isBlank()) ownReceive = repo.receiveCode()
        when (transport) {
            "Bluetooth" -> btThen { nearby.startBluetoothReceive(ownReceive); discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }
            "Wi-Fi Direct" -> wifiThen { nearby.startWifiReceive(ownReceive) }
            "NFC" -> {
                require(packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) { "This phone does not support NFC card emulation" }
                require(android.nfc.NfcAdapter.getDefaultAdapter(this)?.isEnabled == true) { "Turn on NFC in Android Settings first" }
                NfcBus.receiveCode = ownReceive; NfcBus.accept = { repo.acceptOffline(it) }; radioStatus = "Keep this screen open and phone unlocked. The sender taps once to read your wallet, confirms, then taps again to deliver."
            }
        }
    }
    private fun startSender(transport: String) = runSafe {
        stopRadios(); method = transport; receiveRaw = ""; paymentPacket = ""
        when (transport) {
            "Bluetooth" -> btThen { nearby.discoverBluetooth() }
            "Wi-Fi Direct" -> wifiThen { nearby.discoverWifi() }
            "NFC" -> { radioStatus = "Tap the receiver's phone to read their wallet."; nfc.start(onReceive = { raw, _ -> receiveRaw = raw; nfc.stop(); radioStatus = "Recipient read. Confirm below, then tap again to send." }, onError = { localError = it.message ?: "NFC failed" }) }
        }
    }
    private fun pay(amountText: String, note: String) = runSafe {
        val recipientCode = receiveRaw
        val selectedMethod = method
        val r = Protocol.readReceive(recipientCode)
        val value = Protocol.paisa(amountText, if (selectedMethod == "Online") 10_000_000 else Protocol.OFFLINE_LIMIT)
        require(r.getString("walletId") != repo.state.value.walletId) { "Choose someone else's wallet" }
        review = ReviewRequest("Check your payment", value, r.getString("name"),
            "${r.getString("walletId")}\n\n$selectedMethod · NexPay balance\n" +
            if (selectedMethod == "Online") "The server must confirm the transfer." else "The recipient cannot spend this until server settlement.") {
            authenticate {
                if (selectedMethod == "Online") work { repo.enqueue("pay", Protocol.obj("to" to r.getString("walletId"), "amountMinor" to value, "note" to note)); if (repo.state.value.connected && repo.state.value.queued == 0 && repo.state.value.failed.isEmpty()) go("Wallet") }
                else {
                    paymentPacket = repo.makeOffline(recipientCode, value)
                    when(selectedMethod) {
                        "Bluetooth", "Wi-Fi Direct" -> work { val ack = nearby.deliver(paymentPacket); repo.verifyAck(ack, paymentPacket, recipientCode); radioStatus = "Delivered. Waiting for server settlement." }
                        "NFC" -> { radioStatus = "Tap the SAME receiver again to deliver. Keep phones together."; nfc.start(paymentPacket, { raw, ack -> runSafe { requireNotNull(ack); repo.verifyAck(ack, paymentPacket, raw); nfc.stop(); radioStatus = "Delivered. Waiting for server settlement." } }, { localError = it.message ?: "NFC failed; payment is saved in your outbox" }) }
                        else -> { codeDialog = paymentPacket; codeTitle = "Your offline payment" }
                    }
                }
            }
        }
    }
    private fun requestTopup() {
        review = ReviewRequest("Add Rs 5,000?", 500_000, repo.state.value.name, "Once every 24 hours. Demo balance, not a bank deposit.") {
            authenticate { work { repo.enqueue("topup", JSONObject()) } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        nearby = Nearby(this, lifecycleScope, { radioStatus = it }, { localError = it.message ?: "Nearby exchange failed" }, { peers = it }, { receiveRaw = it }, { repo.acceptOffline(it) })
        nfc = NfcReader(this, lifecycleScope)
        appearance = getSharedPreferences("paila_ui", MODE_PRIVATE).getString("appearance", "light") ?: "light"
        setContent {
            val dark = when(appearance) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            val colors = if (dark) darkColorScheme(primary = Lime, onPrimary = Color(0xFF23321D), background = Color(0xFF1C201F), onBackground = Color(0xFFEDF2EB), surface = Color(0xFF282E29), onSurface = Color(0xFFEDF2EB), surfaceVariant = Color(0xFF333C33), onSurfaceVariant = Color(0xFFACB7AD), secondaryContainer = Color(0xFF2E402D), onSecondaryContainer = Color(0xFFB2D5A1), outline = Color(0xFF8A978B), outlineVariant = Color(0xFF424B43))
            else lightColorScheme(primary = Ink, onPrimary = Color.White, background = Warm, onBackground = Ink, surface = Color.White, onSurface = Ink, surfaceVariant = Color(0xFFF2F3EF), onSurfaceVariant = Color(0xFF606660), secondaryContainer = Color(0xFFEDF6E9), onSecondaryContainer = Color(0xFF2D6A45), outline = Color(0xFF858D86), outlineVariant = Color(0xFFDDDFDC))
            MaterialTheme(colorScheme = colors) {
                val state by repo.state.collectAsStateWithLifecycle()
                val scroll = rememberScrollState()
                LaunchedEffect(screen) { scroll.scrollTo(0) }
                Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { if (state.ready && screen != "Send") WalletDock(screen) { go(it) } }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                        Header(state)
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (localError.isNotBlank()) Notice(localError, true) { localError = "" }
                            if (state.error.isNotBlank()) Notice(state.error, true) { repo.clearMessage() }
                            if (state.message.isNotBlank()) Notice(state.message, false) { repo.clearMessage() }
                            if (state.queued > 0) Notice("${state.queued} request saved. Sync again rather than paying twice.", false)
                            val duration = if (ValueAnimator.areAnimatorsEnabled()) 260 else 0
                            AnimatedContent(targetState = if (state.ready) screen else "Setup", transitionSpec = {
                                (fadeIn(tween(duration)) + slideInVertically(tween(duration)) { it / 50 }).togetherWith(fadeOut(tween(if (duration == 0) 0 else 100)))
                            }, label = "Wallet screen") { target ->
                                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    when(target) {
                                        "Setup" -> Setup(state)
                                        "Wallet" -> Home(state)
                                        "Send" -> Send(state)
                                        "Receive" -> Receive(state)
                                        "Offline" -> Offline(state)
                                        "Activity" -> Activity(state)
                                        else -> SettingsScreen(state)
                                    }
                                }
                            }
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                review?.let { item ->
                    ModalBottomSheet(onDismissRequest = { review = null }, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text(item.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp)
                            Text("DEMO BALANCE", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(money(item.amount), fontSize = 40.sp, letterSpacing = (-1.5).sp)
                            CardBlock { Text(item.person, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(item.details, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Primary("Confirm") { review = null; item.action() }
                            TextButton(onClick = { review = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel", fontSize = 16.sp) }
                        }
                    }
                }
                if (codeDialog.isNotBlank()) AlertDialog(onDismissRequest = { codeDialog = "" }, shape = RoundedCornerShape(28.dp), title = { Text(codeTitle) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Qr(codeDialog); Text("Show this to the other phone. Pending until the server confirms.", fontSize = 14.sp) } }, confirmButton = { TextButton(onClick = { copy(codeDialog) }) { Text("Copy code") } }, dismissButton = { TextButton(onClick = { codeDialog = "" }) { Text("Close") } })
            }
        }
    }
    override fun onResume() { super.onResume(); if (repo.state.value.ready) work { repo.sync() } }
    override fun onStop() { if (afterCredential == null && afterPermission == null && afterBluetooth == null) { NfcBus.clear(); if (::nfc.isInitialized) nfc.stop() }; super.onStop() }
    override fun onDestroy() { if (::nearby.isInitialized) nearby.close(); if (::nfc.isInitialized) nfc.stop(); NfcBus.clear(); super.onDestroy() }
    @Composable private fun Header(s: WalletState) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = Lime, shape = RoundedCornerShape(12.dp)) { Text("n.", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) }
                Text("nexpay", fontWeight = FontWeight.SemiBold, fontSize = 25.sp, letterSpacing = (-1).sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(99.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Text("Secure offline", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                if (s.ready) IconButton(onClick = { method = "QR"; go("Receive") }) { Icon(Icons.Outlined.QrCode, "Show my receive code") }
            }
        }
        if (s.ready && screen !in listOf("Wallet", "Activity", "Settings")) TextButton(onClick = { go("Wallet") }, modifier = Modifier.padding(start = 16.dp)) { Icon(Icons.Outlined.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Back to wallet", fontSize = 14.sp) }
    }
    @Composable private fun Setup(s: WalletState) {
        var name by rememberSaveable { mutableStateOf("") }; var server by rememberSaveable { mutableStateOf(s.server.ifBlank { BuildConfig.DEFAULT_SERVER }) }
        val baked = BuildConfig.DEFAULT_SERVER.isNotBlank()
        Box(Modifier.fillMaxWidth().height(212.dp).clip(RoundedCornerShape(28.dp)).background(Warm)) {
            Image(painterResource(R.drawable.wallet_art), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("Take it\noffline.", color = Ink, fontSize = 46.sp, lineHeight = 46.sp, fontWeight = FontWeight.Medium, letterSpacing = (-2).sp)
                Text("Prepare online. Exchange without a signal.", color = Ink, fontSize = 14.sp)
            }
        }
        CardBlock {
            Text("Start with Rs 5,000.", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Rs 5,000", fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
            OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            if (!baked) {
                OutlinedTextField(server, { server = it }, label = { Text("Public HTTPS server") }, placeholder = { Text("https://pay.your-domain.com") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Text("Use the same server on both phones. Localhost isn't public.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Primary("Create my NexPay wallet", !s.busy && name.isNotBlank() && server.isNotBlank()) { work { repo.create(server, name) } }
            Text("No bank account. No cash-out. Demo balance only.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Your key stays on this device. Uninstalling loses access; recovery is not implemented.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    @Composable private fun Home(s: WalletState) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("नमस्ते · Namaste,", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(s.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp) }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) { Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Text(s.name.take(1).uppercase(), fontWeight = FontWeight.SemiBold) } }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Warm)) {
            Image(painterResource(R.drawable.wallet_art), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total balance", fontSize = 14.sp, color = Ink); Text("NPR", fontSize = 14.sp, color = Ink) }
                AnimatedContent(targetState = s.total, transitionSpec = { fadeIn(tween(if (ValueAnimator.areAnimatorsEnabled()) 220 else 0)).togetherWith(fadeOut(tween(0))) }, label = "Confirmed balance") { balance ->
                    Text(money(balance), color = Ink, fontSize = (if (money(balance).length > 13) 30 else 40).sp, lineHeight = 48.sp, letterSpacing = (-1.5).sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Not real money", fontSize = 14.sp, color = Ink)
                    FilledTonalButton(onClick = { requestTopup() }, enabled = !s.busy && s.queued == 0 && System.currentTimeMillis() >= s.nextTopup, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = .9f), contentColor = Ink), contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 44.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Top up", fontSize = 14.sp) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Quick("Send", Icons.Outlined.NorthEast, Modifier.weight(1.08f), true) { method = "Online"; go("Send") }
            Quick("Receive", Icons.Outlined.SouthWest, Modifier.weight(1f)) { method = "QR"; go("Receive") }
        }
        val ready = s.notes.filter { !it.spent && it.status == "reserved" && it.expires > System.currentTimeMillis() }.sumOf { it.amount }
        CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.WifiOff, null, Modifier.size(24.dp))
                Column { Text("Offline pocket", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(if (ready > 0) "Reserved for offline exchange." else "Prepare before you lose signal.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(money(ready), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { go("Offline") }) { Text("Manage notes", fontSize = 14.sp); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("Recent activity", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); TextButton(onClick = { go("Activity") }) { Text("See all", fontSize = 14.sp) } }
        CardBlock { s.activity.take(3).forEach { EntryRow(it) } }
        OutlinedButton(onClick = { work { repo.sync() } }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(99.dp)) { Icon(Icons.Outlined.Sync, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (s.connected) "Connected · Sync wallet" else "Offline · Reconnect and sync", fontSize = 14.sp) }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Methods(current: String, online: Boolean, onSelect: (String) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (if (online) listOf("Online", "QR", "Bluetooth", "Wi-Fi Direct", "NFC") else listOf("QR", "Bluetooth", "Wi-Fi Direct", "NFC")).forEach { item -> FilterChip(selected = current == item, onClick = { onSelect(item) }, label = { Text(item) }, modifier = Modifier.heightIn(min = 44.dp)) }
        }
        Text(methodHint(current, online), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    private fun methodHint(current: String, online: Boolean) = when (current) {
        "Online" -> "Use when both phones have internet. The server confirms the transfer."
        "QR" -> if (online) "Use when the other phone is offline. They scan your code, or you scan theirs." else "Use with no internet. Show your code, or scan the sender's payment code."
        "Bluetooth" -> "Use for phones next to each other. Pair over Bluetooth when asked."
        "Wi-Fi Direct" -> "Use for fast phone-to-phone transfer without a router or mobile data."
        else -> "Use by tapping two NFC phones together twice: once to read, once to send."
    }
    @Composable private fun Send(s: WalletState) {
        var input by rememberSaveable { mutableStateOf("") }; var amount by rememberSaveable { mutableStateOf("") }; var note by rememberSaveable { mutableStateOf("") }
        Text("Send money.", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.3).sp)
        Methods(method, true) { startSender(it) }
        if (radioStatus.isNotBlank()) Notice(radioStatus, false) { radioStatus = "" }
        peers.forEach { peer -> OutlinedButton(onClick = { runSafe { if (method == "Bluetooth") nearby.connectBluetooth(peer.address) else nearby.connectWifi(peer.address) } }, modifier = Modifier.fillMaxWidth()) { Text("${peer.name} · ${peer.address.takeLast(5)}") } }
        if (method in listOf("Online", "QR")) {
            OutlinedButton(onClick = { scan("recipient") }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan receiver's code") }
            OutlinedTextField(input, { input = it }, label = { Text("Or paste a receive code") }, maxLines = 3, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { runSafe { Protocol.readReceive(input.trim()); receiveRaw = input.trim() } }, enabled = input.isNotBlank()) { Text("Use this recipient") }
        }
        val recipient = remember(receiveRaw) { runCatching { Protocol.readReceive(receiveRaw) }.getOrNull() }
        recipient?.let {
            CardBlock { Text("TO", fontSize = 14.sp, letterSpacing = 1.sp); Text(it.getString("name"), fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(it.getString("walletId"), fontFamily = FontFamily.Monospace, fontSize = 14.sp); Text("Check this name and ID with the recipient. Names are self-chosen.", fontSize = 14.sp) }
        }
        OutlinedTextField(amount, { amount = it }, label = { Text("Amount in rupees") }, prefix = { Text("Rs ") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        if (method == "Online") OutlinedTextField(note, { note = it.take(120) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
        else Text("Send any amount up to your offline balance. The remainder stays reserved. Recipient credit remains pending until settlement.", fontSize = 14.sp)
        if (paymentPacket.isBlank()) Primary("Review payment", recipient != null && amount.isNotBlank() && !s.busy && s.queued == 0) { pay(amount, note) }
        else CardBlock {
            Text("Payment is saved", fontWeight = FontWeight.Bold)
            Text("Do not create it again. If delivery failed, show or copy this same code to the same recipient.")
            Primary("Show saved payment") { codeDialog = paymentPacket; codeTitle = "Saved offline payment" }
            TextButton(onClick = { copy(paymentPacket) }) { Text("Copy payment code") }
        }
    }
    @Composable private fun Receive(s: WalletState) {
        var input by rememberSaveable { mutableStateOf("") }
        Text("Receive money.", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.3).sp)
        Methods(method, false) { startReceiver(it) }
        if (radioStatus.isNotBlank()) Notice(radioStatus, false) { radioStatus = "" }
        if (method == "QR") {
            CardBlock { Qr(ownReceive); Text(s.name, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(s.walletId, fontFamily = FontFamily.Monospace, fontSize = 14.sp); TextButton(onClick = { copy(ownReceive) }) { Text("Copy receive code") } }
            Text("Online: the sender scans this code and pays. Offline: scan the payment code they show next.", fontSize = 16.sp)
            Primary("Scan an offline payment") { scan("payment") }
        }
        OutlinedTextField(input, { input = it }, label = { Text("Or paste an offline payment") }, maxLines = 3, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { runSafe { repo.acceptOffline(input.trim()); input = ""; work { repo.sync() } } }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save received payment") }
        Text("Pending receipts are not spendable. A server rejection stays visible in Activity.", fontSize = 14.sp)
    }
    @Composable private fun Offline(s: WalletState) {
        var amount by rememberSaveable { mutableStateOf("100") }
        Text("Your offline pocket.", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.3).sp)
        CardBlock {
            Text("Prepare an offline note", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Rs 2,500 is reserved for you automatically. Top up the pool here; spend any amount from it offline.")
            OutlinedTextField(amount, { amount = it }, label = { Text("Note value in rupees") }, prefix = { Text("Rs ") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Primary("Reserve this amount", !s.busy && s.queued == 0) { runSafe { val n = Protocol.paisa(amount, Protocol.OFFLINE_LIMIT); review = ReviewRequest("Prepare this note?", n, "Offline pocket", "Set aside from available balance. Spend any amount from it offline, valid for 24 hours.") { authenticate { work { repo.enqueue("reserve", Protocol.obj("amountMinor" to n)) } } } } }
            Text("Maximum Rs 5,000 reserved in total. You need internet to prepare notes. An unused expired note can be refunded after a further 7-day redemption window.", fontSize = 14.sp)
        }
        Text("Your notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (s.notes.isEmpty()) Text("No offline notes yet. Reserve your first amount above.")
        s.notes.forEach { n -> CardBlock {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(money(n.amount), fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(if (n.spent && n.status == "reserved") "Payment saved" else n.status) }
            Text("Expires ${date(n.expires)}", fontSize = 14.sp)
            if (n.status == "reserved" && System.currentTimeMillis() >= n.expires + Protocol.REDEEM_GRACE) OutlinedButton(onClick = { authenticate { work { repo.enqueue("reclaim", Protocol.obj("noteId" to n.id)) } } }) { Text("Refund expired note") }
        } }
    }
    @Composable private fun Activity(s: WalletState) {
        Text("Every payment.", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.3).sp)
        s.incoming.forEach { r -> CardBlock { Text("Received ${money(r.amount)} · ${r.status}", fontWeight = FontWeight.Bold); if (r.status == "pending") Text("Waiting for server settlement. Not spendable yet."); if (r.error.isNotBlank()) Text(r.error, color = MaterialTheme.colorScheme.error) } }
        s.outgoing.forEach { r -> CardBlock { Text("To ${r.to} · ${money(r.amount)}", fontWeight = FontWeight.Bold); Text("Offline payment · ${r.status}"); if (r.status in listOf("prepared", "delivered")) TextButton(onClick = { codeDialog = r.packet; codeTitle = "Saved payment to ${r.to}" }) { Text("Show same payment code") } } }
        Text("Server ledger", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        s.activity.forEach { EntryRow(it) }
        if (s.activity.isEmpty()) Text("No settled activity yet.")
    }
    @Composable private fun SettingsScreen(s: WalletState) {
        var server by rememberSaveable { mutableStateOf(s.server) }
        Text("Make it yours.", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.3).sp)
        CardBlock {
            Text("Appearance", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    FilterChip(selected = appearance == value, onClick = { appearance = value; getSharedPreferences("paila_ui", MODE_PRIVATE).edit().putString("appearance", value).apply() }, label = { Text(label, fontSize = 14.sp) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
            Text("Motion follows Android's animation setting.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CardBlock {
            Text("Connection", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (s.connected) "Connected to the NexPay network" else "Not connected — saved data is shown")
            if (BuildConfig.DEFAULT_SERVER.isBlank()) {
                OutlinedTextField(server, { server = it }, label = { Text("Server origin") }, modifier = Modifier.fillMaxWidth())
                Text("Changing the address is allowed only if the issuer signing key stays the same.", fontSize = 14.sp)
                Primary("Verify and save address", !s.busy) { work { repo.changeEndpoint(server); repo.sync() } }
            }
            Text("Issuer fingerprint", fontWeight = FontWeight.Bold)
            Text(s.issuer.chunked(16).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            OutlinedButton(onClick = { work { repo.sync() } }, enabled = !s.busy) { Text("Sync now") }
        }
        CardBlock {
            Text("Permissions & connections", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Camera is requested only when scanning. Nearby devices are requested when you choose Bluetooth or Wi-Fi Direct. NFC is controlled in Android Settings.")
            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("Open app permissions") }
            TextButton(onClick = { runSafe { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text("Bluetooth settings") }
            TextButton(onClick = { runSafe { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }) { Text("Wi-Fi settings") }
            TextButton(onClick = { runSafe { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } }) { Text("NFC settings") }
            TextButton(onClick = { runSafe { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }) { Text("Screen lock settings") }
        }
        if (s.failed.isNotEmpty()) CardBlock { Text("Rejected requests", fontWeight = FontWeight.Bold); s.failed.forEach { Text(it, color = MaterialTheme.colorScheme.error) }; TextButton(onClick = { repo.clearFailed() }) { Text("Acknowledge these errors") } }
        CardBlock {
            Text("Balance", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Rs 5,000 is granted on first registration. You can add another Rs 5,000 once every 24 hours. Demo balance — no banks connected.")
            Primary("Add Rs 5,000", !s.busy && s.queued == 0 && System.currentTimeMillis() >= s.nextTopup) { requestTopup() }
            if (System.currentTimeMillis() < s.nextTopup) Text("Next top-up: ${date(s.nextTopup)}", fontSize = 14.sp)
        }
        Text("NexPay 0.3 · Demo network\nDevice-held keys. Encrypted local storage. Signed payments. Demo balance only — not connected to banks or real money. Do not disable Play Protect to install this app.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    @Composable private fun EntryRow(e: Entry) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) { Icon(if (e.amount >= 0) Icons.Outlined.SouthWest else Icons.Outlined.NorthEast, null, Modifier.padding(12.dp).size(22.dp)) }
            Column(Modifier.weight(1f)) { Text(when(e.kind) { "welcome" -> "Welcome credit"; "test_topup" -> "Credit added"; "offline_reserve" -> "Offline note reserved"; "offline_payment" -> "Offline payment settled"; "offline_refund" -> "Expired note refund"; else -> e.name }, fontWeight = FontWeight.Medium); Text(date(e.created), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text((if (e.amount > 0) "+" else "−") + money(kotlin.math.abs(e.amount)), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}
@Composable private fun WalletDock(screen: String, onSelect: (String) -> Unit) {
    Surface(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp).navigationBarsPadding(), shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .85f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0), modifier = Modifier.height(72.dp)) {
            listOf(Triple("Wallet", Icons.Outlined.AccountBalanceWallet, "Wallet"), Triple("Activity", Icons.Outlined.ReceiptLong, "Activity"), Triple("Settings", Icons.Outlined.Settings, "Settings")).forEach { (id, icon, label) ->
                val selected = screen == id || (id == "Wallet" && screen !in listOf("Activity", "Settings"))
                NavigationBarItem(selected = selected, onClick = { onSelect(id) }, icon = { Icon(icon, null, Modifier.size(21.dp)) }, label = { Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surface, selectedIconColor = MaterialTheme.colorScheme.onSurface, selectedTextColor = MaterialTheme.colorScheme.onSurface, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }
}
@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content) } }
@Composable private fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }; val pressed by interaction.collectIsPressedAsState()
    val motion = ValueAnimator.areAnimatorsEnabled()
    val scale by animateFloatAsState(if (pressed && enabled && motion) .975f else 1f, animationSpec = if (motion) spring(dampingRatio = .8f, stiffness = 550f) else tween(0), label = "Button press")
    Button(onClick = onClick, enabled = enabled, interactionSource = interaction, shape = RoundedCornerShape(99.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).scale(scale), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
        Text(text, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Outlined.ArrowForward, null, Modifier.size(20.dp))
    }
}
@Composable private fun Quick(text: String, icon: ImageVector, modifier: Modifier, prominent: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(99.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), colors = ButtonDefaults.buttonColors(containerColor = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}
@Composable private fun Notice(text: String, error: Boolean, dismiss: (() -> Unit)? = null) { Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, Modifier.size(20.dp)); Text(text, Modifier.weight(1f), fontSize = 14.sp, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer); if (dismiss != null) IconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.Close, "Dismiss message") } } } }
@Composable private fun Qr(raw: String) {
    val result = remember(raw) { runCatching {
        require(raw.isNotBlank())
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 768, 768, mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
        Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply { val pixels = IntArray(768 * 768) { i -> if (matrix.get(i % 768, i / 768)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }; setPixels(pixels, 0, 768, 0, 0, 768, 768) }
    } }
    result.getOrNull()?.let { Image(it.asImageBitmap(), "NexPay signed payment QR code", Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White)) } ?: Text("QR code could not be displayed. Use Copy code instead.")
}
