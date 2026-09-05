package np.nexpay.wallet

import np.nexpay.wallet.core.Protocol
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class ProtocolTest {
    @Test fun amountsUseIntegerPaisa() { assertEquals(100000L, Protocol.paisa("1000")); assertEquals(12575L, Protocol.paisa("125.75")); assertEquals(1L, Protocol.paisa("0.01")) }
    @Test fun malformedAmountsAreRejected() { listOf("0", "-1", "1.001", "NaN", "1e3", "1,000", "").forEach { text -> assertThrows(IllegalArgumentException::class.java) { Protocol.paisa(text) } } }
    @Test fun signaturesAreDomainSeparated() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = Protocol.b64(pair.public.encoded); val payload = Protocol.obj("v" to 1, "amount" to 123)
        val packet = Protocol.sign("test", payload, pair.private)
        assertEquals(123, Protocol.verify("test", packet, pub).getInt("amount"))
        assertThrows(IllegalArgumentException::class.java) { Protocol.verify("payment", packet, pub) }
    }
    @Test fun canonicalKeysAreSorted() { assertEquals("{\"a\":2,\"b\":1}", Protocol.canonical(Protocol.obj("b" to 1, "a" to 2))) }
    @Test fun amountLimitEnforced() { assertThrows(IllegalArgumentException::class.java) { Protocol.paisa("5001", Protocol.OFFLINE_LIMIT) } }
}
