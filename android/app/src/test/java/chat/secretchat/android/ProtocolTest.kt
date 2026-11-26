// SPDX-License-Identifier: AGPL-3.0-only
package chat.secretchat.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal fun uuid() = UUID.randomUUID().toString()
internal fun Protocol.contact() = Contact(uuid(), uuid(), publicBundle().getString("identityKey"), false)
internal fun Protocol.bundleFor(contact: Contact) = publicBundle().put("accountId", contact.accountId)
    .put("deviceId", contact.deviceId).apply {
        pool.pending.firstOrNull()?.let { put("oneTimePrekey", json("id" to it.id, "material" to it.material)) }
    }

class ProtocolTest {
    @Test fun bidirectionalAndOutOfOrderWithAndWithoutOneTimePrekey() {
        for (withOneTime in listOf(true, false)) {
            var alice = Protocol.generate()
            var bob = Protocol.generate().let { if (withOneTime) it.prepareBatch() else it }
            val a = alice.contact(); val b = bob.contact()
            val first = uuid(); val second = uuid(); val reply = uuid()
            val (firstState, firstPacket) = alice.seal(a.accountId, b, first, "你好 👋", bob.bundleFor(b))
            val (secondState, secondPacket) = firstState.seal(a.accountId, b, second, "乱序", null)
            alice = secondState
            val later = bob.open(b.accountId, a, second, secondPacket)
            assertEquals("乱序", later.second)
            val earlier = later.first.open(b.accountId, a, first, firstPacket)
            assertEquals("你好 👋", earlier.second)
            bob = earlier.first
            assertFalse(1 in bob.pool.records)
            val (_, replyPacket) = bob.seal(b.accountId, a, reply, "收到", null)
            assertEquals("收到", alice.open(a.accountId, b, reply, replyPacket).second)
            assertThrows(Exception::class.java) { bob.open(b.accountId, a, first, firstPacket) }
        }
    }

    @Test fun modifiedCiphertextAndSubstitutedMetadataCannotAdvanceSession() {
        val alice = Protocol.generate(); val bob = Protocol.generate()
        val a = alice.contact(); val b = bob.contact(); val mid = uuid()
        val (_, sealed) = alice.seal(a.accountId, b, mid, "authenticated", bob.bundleFor(b))
        val tampered = sealed.bytes64().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }.b64()
        assertThrows(Exception::class.java) { bob.open(b.accountId, a, mid, tampered) }
        assertThrows(Exception::class.java) { bob.open(b.accountId, a, uuid(), sealed) }
        assertThrows(Exception::class.java) { bob.open(uuid(), a, mid, sealed) }
        assertThrows(Exception::class.java) { bob.open(b.accountId, a.copy(identityKey = b.identityKey), mid, sealed) }
        assertEquals("authenticated", bob.open(b.accountId, a, mid, sealed).second)
    }

    @Test fun rejectsDirectorySubstitutionAndInvalidSignature() {
        val alice = Protocol.generate(); val bob = Protocol.generate()
        val a = alice.contact(); val b = bob.contact()
        for (field in listOf("accountId", "deviceId", "identityKey", "signedPrekey")) {
            val keys = bob.bundleFor(b)
            if (field == "signedPrekey") {
                val record = JSONObject(keys.getString(field).bytes64().decodeToString()).put("signature", ByteArray(64).b64())
                keys.put(field, record.toString().toByteArray().b64())
            } else keys.put(field, uuid())
            assertThrows(Exception::class.java) { alice.seal(a.accountId, b, uuid(), "test", keys) }
        }
    }

    @Test fun checksUtf8ByteLimitAndBlankMessages() {
        validateText("字".repeat(5461) + "a")
        for (text in listOf(" \n", "", "字".repeat(5462))) {
            assertThrows(IllegalArgumentException::class.java) { validateText(text) }
        }
    }

    @Test fun jsonEscapingMustFitServerEnvelopeBeforeCommittingRatchet() {
        val alice = Protocol.generate()
        val bob = Protocol.generate()
        val peer = bob.contact()
        val escaped = 0.toChar().toString().repeat(16_384)
        validateText(escaped)
        assertThrows(IllegalArgumentException::class.java) {
            alice.seal(uuid(), peer, uuid(), escaped, bob.bundleFor(peer))
        }
        assertTrue(alice.bindings.isEmpty())
    }

    @Test fun opensSwiftGeneratedPrekeyEnvelope() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/ios-v0.108.6.json")).bufferedReader().use { it.readText() }
        val v = JSONObject(raw)
        val recipient = Protocol(v.getString("identity"), v.getInt("registration"), v.getString("signed"), v.getString("kyber"),
            PrekeyBatch(1, mapOf(1 to v.getString("oneTimeRecord"))))
        val sender = Contact(v.getString("sender"), uuid(), v.getString("senderIdentity"), false)
        val result = recipient.open(v.getString("recipient"), sender, v.getString("messageId"), v.getString("ciphertext"))
        assertEquals("来自 iOS · 👋", result.second)
        assertTrue(result.first.pool.records.isEmpty())
    }
}
