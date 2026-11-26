// SPDX-License-Identifier: AGPL-3.0-only
package chat.secretchat.android

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class ServiceAndVaultTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sharedInvitationsPreserveCaseDeploymentPathAndOrigin() {
        val ticket = Invitation("https://example.test/secretchat", "aBc-7")
        assertEquals(ticket, Invitation.parse(ticket.sharedText(), ""))
        assertEquals(ticket, Invitation.parse(ticket.sharedText(), ticket.root + "/"))
        assertEquals(ticket, Invitation.parse(" aBc-7 ", ticket.root))
        for (bad in listOf("https://example.test/other", "https://other.test/secretchat")) {
            assertThrows(IllegalArgumentException::class.java) { Invitation.parse(ticket.sharedText(), bad) }
        }
        for (invalid in listOf("http://example.test", "https://user@example.test", "https://example.test?q=x", "https://example.test/#frag")) {
            assertThrows(IllegalArgumentException::class.java) { serviceRoot(invalid) }
        }
    }

    private fun client(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val (code, data) = reply(request)
        Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("response")
            .header("X-Request-ID", "req-123").body(data.toResponseBody()).build()
    }.build()
    private fun Request.jsonBody() = JSONObject(Buffer().also { body!!.writeTo(it) }.readUtf8())

    @Test fun registrationUsesSwiftDirectoryFieldsAndOnlyPublicMaterial() {
        val keys = Protocol.generate()
        val account = uuid(); val device = uuid(); val challenge = uuid()
        for (proof in listOf(null, challenge)) {
            val service = Service("https://example.test/secretchat", client = client { req ->
                assertEquals("POST", req.method)
                assertEquals("/secretchat/api/accounts", req.url.encodedPath)
                assertNull(req.header("Authorization"))
                val body = req.jsonBody()
                assertEquals("aBc", body.getString("inviteCode"))
                assertEquals(proof == null, body.has("email"))
                assertEquals(proof != null, body.has("code"))
                assertEquals(proof != null, body.has("challengeId"))
                assertFalse(body.toString().contains(keys.identity))
                assertFalse(body.toString().contains(keys.kyber))
                val protocol = JSONObject(body.getString("protocolPrekeys").bytes64().decodeToString())
                assertEquals(keys.registration, protocol.getInt("registrationId"))
                assertEquals(1, protocol.getJSONObject("kyber").getInt("id"))
                201 to json("accountId" to account, "deviceId" to device, "token" to "token").toString()
            })
            assertEquals(Account(account, device, "token"), service.register("member@example.test", "aBc", proof, "123456", keys))
        }
    }

    @Test fun receiptAndDirectoryRoutesUseBearerPrefixAndRejectWrongEvidence() {
        val ref = uuid(); var calls = 0
        val service = Service("https://example.test/secretchat", "credential", client { req ->
            assertEquals("Bearer credential", req.header("Authorization"))
            calls++
            when (req.url.encodedPath) {
                "/secretchat/api/contacts" -> 200 to "[]"
                "/secretchat/api/mailbox/envelopes" -> 202 to json("reference" to ref, "expiresAtMs" to 999).toString()
                "/secretchat/api/mailbox/outcomes/" + ref -> 200 to json("reference" to uuid(), "status" to "received").toString()
                else -> error("unexpected route")
            }
        })
        assertTrue(service.contacts().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { service.submit(Outgoing(uuid(), uuid(), uuid(), 1000, "AA==")) }
        assertThrows(IllegalArgumentException::class.java) { service.outcome(ref) }
        assertEquals(3, calls)
    }

    @Test fun failedHttpRequestDoesNotExposeBodyOrToken() {
        val service = Service("https://example.test", "private-token", client { 401 to "private-error" })
        val failure = assertThrows(HttpFailure::class.java) { service.contacts() }
        assertEquals(401, failure.status)
        assertTrue(failure.message!!.contains("req-123"))
        assertFalse(failure.message!!.contains("private"))
        assertFalse(Service.http.followRedirects)
    }

    @Test fun encryptedSnapshotRestoresAllStateAndRejectsTamperingAndWrongKey() {
        fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secret = key()
        val file = File(temp.root, "profile")
        val vault = Vault(file) { secret }
        val start = vault.open()
        val peer = uuid()
        val msg = Message(uuid(), peer, true, "history-plaintext", Delivery.PENDING)
        val state = start.copy(account = Account(uuid(), uuid(), "private-device-token"), cursor = 31, messages = listOf(msg),
            protocol = start.protocol.prepareBatch(), acks = setOf(uuid()))
        vault.save(state)
        val bytes = file.readBytes()
        assertEquals(state, Vault(file) { secret }.open())
        for (s in listOf(msg.text!!, state.account!!.token, state.protocol.identity)) assertFalse(bytes.decodeToString().contains(s))
        vault.save(state)
        assertFalse(bytes.contentEquals(file.readBytes()))
        assertThrows(AEADBadTagException::class.java) { Vault(file) { key() }.open() }
        file.writeBytes(bytes.apply { this[lastIndex] = (last().toInt() xor 1).toByte() })
        assertThrows(AEADBadTagException::class.java) { vault.open() }
        assertArrayEquals(bytes, file.readBytes())
    }
}
