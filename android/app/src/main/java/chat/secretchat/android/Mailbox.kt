// SPDX-License-Identifier: AGPL-3.0-only
package chat.secretchat.android

import java.util.UUID

internal enum class Delivery(val label: String) {
    PENDING("本机待提交"), ACCEPTED("服务端已接收"), RECEIVED("对方设备已收到"),
    EXPIRED("已过期，未获设备确认"), UNCONFIRMED("投递结果未确认"), UNREADABLE("无法解密")
}

internal data class Message(val id: String, val peer: String, val outgoing: Boolean, val text: String?,
                            val status: Delivery, val reference: String? = null)

internal data class Profile(val protocol: Protocol, val root: String? = null, val account: Account? = null,
                           val contacts: List<Contact> = emptyList(), val messages: List<Message> = emptyList(),
                           val outbox: List<Outgoing> = emptyList(), val acks: Set<String> = emptySet(), val cursor: Long = 0) {
    fun restriction(peer: Contact?): String? {
        if (peer == null || peer !in contacts) return "联系人已变更，请刷新目录"
        if (peer.blocked) return "已拉黑，收发暂停"
        val pin = protocol.bindings[peer.accountId] ?: return null
        return if (pin.device != peer.deviceId || pin.identity != peer.identityKey) "联系人设备身份已变化，收发暂停" else null
    }

    fun consume(item: Envelope, now: Long): Profile {
        val staged = copy(acks = acks + item.reference)
        val peer = contacts.find { it.accountId == item.senderAccountId } ?: return staged
        if (item.expiresAtMs <= now || peer.deviceId != item.senderDeviceId || restriction(peer) != null) return staged
        if (messages.any { !it.outgoing && it.peer == peer.accountId && it.id == item.messageId }) return staged
        val (next, text) = try {
            protocol.open(requireNotNull(account).accountId, peer, item.messageId, item.ciphertext)
        } catch (_: Exception) {
            return staged.copy(messages = messages + Message(item.messageId, peer.accountId, false, null, Delivery.UNREADABLE))
        }
        return staged.copy(protocol = next, messages = messages + Message(item.messageId, peer.accountId, false, text, Delivery.RECEIVED))
    }
}

// Owned by a single application-scoped worker. Publish a new snapshot only after storage succeeds.
internal class Device(initial: Profile, private val save: (Profile) -> Unit,
                      private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile var state = initial
        private set

    fun commit(next: Profile) {
        save(next)
        state = next
    }

    fun queue(peer: Contact, text: String, api: Mailbox) {
        validateText(text)
        state.restriction(peer)?.let { throw IllegalStateException(it) }
        val bundle = if (peer.accountId in state.protocol.bindings) null else api.bundle(peer.accountId)
        val mid = UUID.randomUUID().toString()
        val (next, encrypted) = state.protocol.seal(requireNotNull(state.account).accountId, peer, mid, text, bundle)
        val envelope = Outgoing(mid, peer.accountId, peer.deviceId, clock() + 6 * 86_400_000L, encrypted)
        commit(state.copy(protocol = next, outbox = state.outbox + envelope,
            messages = state.messages + Message(mid, peer.accountId, true, text, Delivery.PENDING)))
    }

    fun sync(api: Mailbox) {
        val directory = api.contacts()
        if (state.contacts != directory) commit(state.copy(contacts = directory))
        receive(api)
        acknowledge(api)
        submit(api)
        reconcile(api)
        replenish(api)
    }

    private fun receive(api: Mailbox) {
        var next = state
        var boundary: Long? = null
        do {
            val page = api.page(next.cursor, boundary)
            page.validate(next.cursor, boundary)
            boundary = page.through
            for (envelope in page.envelopes) next = next.consume(envelope, clock())
            next = next.copy(cursor = page.nextAfter)
        } while (!page.done)
        if (next.cursor != state.cursor) commit(next)
    }

    private fun acknowledge(api: Mailbox) {
        for (ref in state.acks) {
            try { api.ack(ref) } catch (failure: HttpFailure) {
                if (failure.status !in listOf(404, 410)) throw failure
            }
            commit(state.copy(acks = state.acks - ref))
        }
    }

    private fun submit(api: Mailbox) {
        for (item in state.outbox) {
            val peer = state.contacts.find { it.accountId == item.recipientAccountId }
            if (state.restriction(peer) != null || peer?.deviceId != item.recipientDeviceId) continue
            val ref = try { api.submit(item) } catch (failure: HttpFailure) {
                if (failure.status == 409) continue
                if (failure.status != 400 || item.expiresAtMs > clock()) throw failure
                null
            }
            val status = if (ref == null) Delivery.UNCONFIRMED else Delivery.ACCEPTED
            val messages = state.messages.map {
                if (it.outgoing && it.id == item.messageId) it.copy(status = status, reference = ref) else it
            }
            commit(state.copy(outbox = state.outbox - item, messages = messages))
        }
    }

    private fun reconcile(api: Mailbox) {
        for (msg in state.messages.filter { it.outgoing && it.status == Delivery.ACCEPTED }) {
            val status = try { api.outcome(requireNotNull(msg.reference)) } catch (failure: HttpFailure) {
                if (failure.status != 404) throw failure
                Delivery.UNCONFIRMED
            }
            if (status != msg.status) commit(state.copy(messages = state.messages.map {
                if (it == msg) it.copy(status = status) else it
            }))
        }
    }

    private fun replenish(api: Mailbox) {
        if (state.protocol.pool.pending.isEmpty()) {
            if (api.remaining() >= 4) return
            commit(state.copy(protocol = state.protocol.prepareBatch()))
        }
        api.upload(state.protocol.pool.pending)
        commit(state.copy(protocol = state.protocol.copy(pool = state.protocol.pool.copy(pending = emptyList()))))
    }
}
