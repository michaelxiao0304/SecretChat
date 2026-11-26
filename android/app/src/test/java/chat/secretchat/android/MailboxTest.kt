// SPDX-License-Identifier: AGPL-3.0-only
package chat.secretchat.android

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

private class FakeMailbox(val keys: Protocol) : Mailbox {
    val peer = keys.contact()
    var directory = listOf(peer)
    val ref = uuid()
    val submitted = mutableListOf<Outgoing>()
    val uploads = mutableListOf<List<OneTime>>()
    val acknowledged = mutableListOf<String>()
    var pull: (Long, Long?) -> Page = { after, _ -> Page(emptyList(), after, after, true) }
    var submitError: Exception? = null
    var ackError: Exception? = null
    var uploadError: Exception? = null
    var receipt = Delivery.ACCEPTED
    var stock = 12
    var claims = 0
    override fun contacts() = directory
    override fun bundle(peer: String) = keys.bundleFor(this.peer).also { claims++ }
    override fun remaining() = stock
    override fun upload(keys: List<OneTime>) { uploads.add(keys); uploadError?.let { throw it } }
    override fun page(after: Long, through: Long?) = pull(after, through)
    override fun ack(reference: String) { acknowledged.add(reference); ackError?.let { throw it } }
    override fun submit(item: Outgoing): String { submitted.add(item); submitError?.let { throw it }; return ref }
    override fun outcome(reference: String) = receipt
}

class MailboxTest {
    private val api = FakeMailbox(Protocol.generate())
    private val local = Protocol.generate()
    private val me = local.contact()
    private val initial = Profile(local, "https://example.test", Account(me.accountId, me.deviceId, "token"), listOf(api.peer))
    private var disk = initial
    private fun restart() = Device(disk, { disk = it }) { 1_000 }

    @Test fun interruptedSubmissionResendsExactEnvelopeAndRequiresDeviceReceipt() {
        val device = restart()
        device.queue(api.peer, "first", api)
        api.submitError = IOException("lost response")
        assertThrows(IOException::class.java) { device.sync(api) }
        assertEquals(Delivery.PENDING, disk.messages.single().status)
        api.submitError = null
        restart().sync(api)
        assertEquals(api.submitted.first(), api.submitted.last())
        assertEquals(Delivery.ACCEPTED, disk.messages.single().status)
        api.receipt = Delivery.RECEIVED
        restart().sync(api)
        assertEquals(Delivery.RECEIVED, disk.messages.single().status)
        assertTrue(disk.outbox.isEmpty())
    }

    @Test fun changedKeyDeviceBlockAndRemovalSuspendPendingAndNewSubmissions() {
        for (newDirectory in listOf(listOf(api.peer.copy(deviceId = uuid())), listOf(api.peer.copy(identityKey = me.identityKey)),
                emptyList(), listOf(api.peer.copy(blocked = true)))) {
            disk = initial
            val device = restart()
            device.queue(api.peer, "pending", api)
            api.directory = newDirectory
            device.sync(api)
            val claimsBefore = api.claims
            assertThrows(IllegalStateException::class.java) { device.queue(api.peer, "again", api) }
            assertEquals(claimsBefore, api.claims)
            assertEquals(1, disk.outbox.size)
            assertTrue(api.submitted.isEmpty())
        }
    }

    private fun incoming(): Envelope {
        val mid = uuid()
        val (_, packet) = api.keys.seal(api.peer.accountId, me, mid, "original", local.bundleFor(me))
        return Envelope(1, uuid(), mid, api.peer.accountId, api.peer.deviceId, 2_000, packet)
    }

    @Test fun crossPageFailurePublishesNothingAndLostAckSurvivesRestart() {
        val message = incoming()
        var breakPage = true
        api.pull = { after, boundary ->
            when (after) {
                0L -> Page(listOf(message), 2, 1, false)
                1L -> {
                    assertEquals(2L, boundary)
                    if (breakPage) throw IOException("second page unavailable")
                    Page(listOf(message.copy(sequence = 2, reference = uuid())), 2, 2, true)
                }
                else -> Page(emptyList(), after, after, true)
            }
        }
        assertThrows(IOException::class.java) { restart().sync(api) }
        assertEquals(initial, disk)
        assertTrue(api.acknowledged.isEmpty())
        breakPage = false
        api.ackError = IOException("ack lost")
        assertThrows(IOException::class.java) { restart().sync(api) }
        assertEquals("original", disk.messages.single().text)
        assertEquals(2, disk.acks.size)
        assertEquals(2L, disk.cursor)
        api.ackError = null
        restart().sync(api)
        assertEquals(1, disk.messages.size)
        assertTrue(disk.acks.isEmpty())
    }

    @Test fun storageFailureCannotPublishRatchetOrAck() {
        val device = Device(initial, { throw IOException("disk full") }) { 1_000 }
        val item = incoming()
        api.pull = { _, _ -> Page(listOf(item), 1, 1, true) }
        assertThrows(IOException::class.java) { device.sync(api) }
        assertTrue(api.acknowledged.isEmpty())
        assertEquals(initial, device.state)
        assertThrows(IOException::class.java) { device.queue(api.peer, "outgoing", api) }
        assertTrue(api.submitted.isEmpty())
        assertEquals(initial.protocol.bindings, device.state.protocol.bindings)
    }

    @Test fun ignoredAndUnreadableMessagesAcknowledgeWithoutLeakingText() {
        val item = incoming()
        for (rejected in listOf(item.copy(expiresAtMs = 1_000), item.copy(senderAccountId = uuid()), item.copy(senderDeviceId = uuid()))) {
            val next = initial.consume(rejected, 1_000)
            assertEquals(setOf(item.reference), next.acks)
            assertTrue(next.messages.isEmpty())
        }
        val blocked = initial.copy(contacts = listOf(api.peer.copy(blocked = true))).consume(item, 1_000)
        assertTrue(blocked.messages.isEmpty())
        val unreadable = initial.consume(item.copy(ciphertext = "AAAA"), 1_000)
        assertEquals(Delivery.UNREADABLE, unreadable.messages.single().status)
        assertNull(unreadable.messages.single().text)
        assertEquals(unreadable, unreadable.consume(item, 1_000))
        assertEquals(local, unreadable.protocol)
    }

    @Test fun oneTimeUploadRetriesDurableBatchAndAllocatesNewIds() {
        api.stock = 0
        api.uploadError = IOException()
        assertThrows(IOException::class.java) { restart().sync(api) }
        assertEquals(16, disk.protocol.pool.records.size)
        api.uploadError = null
        restart().sync(api)
        assertEquals(api.uploads[0], api.uploads[1])
        assertTrue(disk.protocol.pool.pending.isEmpty())
        restart().sync(api)
        assertTrue(api.uploads.first().map { it.id }.intersect(api.uploads.last().map { it.id }.toSet()).isEmpty())
    }

    @Test fun serverEvidenceDeterminesExpiredAndUnconfirmedOutcomes() {
        val device = restart()
        device.queue(api.peer, "expires", api)
        api.receipt = Delivery.EXPIRED
        device.sync(api)
        assertEquals(Delivery.EXPIRED, disk.messages.single().status)
        device.queue(api.peer, "lost receipt", api)
        val later = Device(disk, { disk = it }) { Long.MAX_VALUE }
        api.submitError = HttpFailure(400, null)
        later.sync(api)
        assertEquals(Delivery.UNCONFIRMED, disk.messages.last().status)
    }

    @Test fun paginationRejectsMovingBoundaryStalledCursorAndSkippedEnvelope() {
        assertThrows(IllegalArgumentException::class.java) { Page(emptyList(), 6, 6, true).validate(2, 5) }
        assertThrows(IllegalArgumentException::class.java) { Page(emptyList(), 6, 2, false).validate(2, 6) }
        assertThrows(IllegalArgumentException::class.java) { Page(listOf(incoming()), 5, 4, false).validate(0, null) }
    }
}
