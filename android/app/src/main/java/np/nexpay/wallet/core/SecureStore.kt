package np.nexpay.wallet.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "wallet.aesgcm"))
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val signingAlias = "paila.wallet.sign.v1"
    private val storageAlias = "paila.wallet.store.v1"
    init {
        check(!file.baseFile.exists() || (ks.containsAlias(signingAlias) && ks.containsAlias(storageAlias))) { "Wallet encryption key unavailable. Do not clear app storage; recover from your server operator." }
        if (!ks.containsAlias(signingAlias)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(signingAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build()); generateKeyPair()
        }
        if (!ks.containsAlias(storageAlias)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(storageAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build()); generateKey()
        }
    }
    val publicKey: String get() = Protocol.b64(ks.getCertificate(signingAlias).publicKey.encoded)
    fun sign(kind: String, data: JSONObject) = Protocol.sign(kind, data, ks.getKey(signingAlias, null) as PrivateKey)
    @Synchronized fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Wallet storage is damaged" }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, ks.getKey(storageAlias, null) as SecretKey, GCMParameterSpec(128, bytes.copyOfRange(1, 13))); updateAAD("paila.store.v1".toByteArray()) }
        return JSONObject(String(c.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized fun update(block: (JSONObject) -> Unit): JSONObject {
        val data = read(); block(data)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, ks.getKey(storageAlias, null) as SecretKey); updateAAD("paila.store.v1".toByteArray()) }
        val bytes = byteArrayOf(1) + c.iv + c.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        return data
    }
}
