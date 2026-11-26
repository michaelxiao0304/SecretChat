// SPDX-License-Identifier: AGPL-3.0-only
package chat.secretchat.android

import org.json.JSONObject
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.*
import org.signal.libsignal.protocol.message.*
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
internal fun String.bytes64(): ByteArray = Base64.getDecoder().decode(this)
internal fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value) }
}

internal fun id(value: String): String {
    val canonical = UUID.fromString(value).toString()
    require(canonical.equals(value, ignoreCase = true)) { "无效的标识符" }
    return canonical
}

internal fun validateText(text: String) {
    require(text.isNotBlank() && text.toByteArray().size <= 16_384) { "请输入 1 至 16384 字节的非空文字" }
}

internal data class Binding(val device: String, val identity: String, val session: String)
internal data class OneTime(val id: Int, val material: String)
internal data class PrekeyBatch(val last: Int = 0, val records: Map<Int, String> = emptyMap(),
                                val pending: List<OneTime> = emptyList())

internal data class Protocol(
    val identity: String, val registration: Int, val signed: String, val kyber: String,
    val pool: PrekeyBatch = PrekeyBatch(), val bindings: Map<String, Binding> = emptyMap(),
    val usedBaseKeys: Set<String> = emptySet()
) {
    companion object {
        fun generate(): Protocol {
            val pair = IdentityKeyPair.generate()
            val ec = ECKeyPair.generate()
            val kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val now = System.currentTimeMillis()
            return Protocol(pair.serialize().b64(), 1 + SecureRandom().nextInt(16383),
                SignedPreKeyRecord(1, now, ec, pair.privateKey.calculateSignature(ec.publicKey.serialize())).serialize().b64(),
                KyberPreKeyRecord(1, now, kem, pair.privateKey.calculateSignature(kem.publicKey.serialize())).serialize().b64())
        }
    }

    fun publicBundle(): JSONObject {
        val ec = SignedPreKeyRecord(signed.bytes64())
        val kem = KyberPreKeyRecord(kyber.bytes64())
        fun key(id: Int, public: ByteArray, sig: ByteArray) =
            json("id" to id, "key" to public.b64(), "signature" to sig.b64())
        fun JSONObject.encoded() = toString().toByteArray().b64()
        return json(
            "identityKey" to IdentityKeyPair(identity.bytes64()).publicKey.serialize().b64(),
            "signedPrekey" to key(ec.id, ec.keyPair.publicKey.serialize(), ec.signature).encoded(),
            "protocolPrekeys" to json("registrationId" to registration,
                "kyber" to key(kem.id, kem.keyPair.publicKey.serialize(), kem.signature)).encoded())
    }

    fun prepareBatch(): Protocol {
        if (pool.pending.isNotEmpty()) return this
        check(pool.last <= Int.MAX_VALUE - 16)
        val records = pool.records.toMutableMap()
        val pending = (pool.last + 1 .. pool.last + 16).map { n ->
            val pair = ECKeyPair.generate()
            records[n] = PreKeyRecord(n, pair).serialize().b64()
            OneTime(n, pair.publicKey.serialize().b64())
        }
        return copy(pool = PrekeyBatch(pending.last().id, records, pending))
    }

    private class Working(state: Protocol, val peer: Contact, me: String) :
        InMemorySignalProtocolStore(IdentityKeyPair(state.identity.bytes64()), state.registration) {
        val remote = SignalProtocolAddress(peer.accountId, 1)
        val local = SignalProtocolAddress(me, 1)
        val spent = state.usedBaseKeys.toMutableSet()
        val cipher = SessionCipher(this, local, remote)

        init {
            val binding = state.bindings[peer.accountId]
            if (binding != null) {
                require(binding.device == peer.deviceId && binding.identity == peer.identityKey) { "联系人设备身份已变化" }
                storeSession(remote, SessionRecord(binding.session.bytes64()))
            }
            // Pin the directory identity before processing an incoming prekey message.
            saveIdentity(remote, IdentityKey(peer.identityKey.bytes64()))
            storeSignedPreKey(1, SignedPreKeyRecord(state.signed.bytes64()))
            storeKyberPreKey(1, KyberPreKeyRecord(state.kyber.bytes64()))
            state.pool.records.forEach { (id, data) -> storePreKey(id, PreKeyRecord(data.bytes64())) }
        }

        override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
            if (!spent.add(baseKey.serialize().b64())) throw ReusedBaseKeyException()
            super.markKyberPreKeyUsed(kyberPreKeyId, signedPreKeyId, baseKey)
        }

        fun commit(state: Protocol): Protocol {
            require(getIdentity(remote).serialize().b64() == peer.identityKey && containsSession(remote))
            val binding = Binding(peer.deviceId, peer.identityKey, loadSession(remote).serialize().b64())
            return state.copy(bindings = state.bindings + (peer.accountId to binding),
                pool = state.pool.copy(records = state.pool.records.filterKeys { containsPreKey(it) }),
                usedBaseKeys = spent.toSet())
        }
    }

    fun seal(me: String, peer: Contact, messageId: String, text: String, bundle: JSONObject?): Pair<Protocol, String> {
        validateText(text)
        val working = Working(this, peer, me)
        if (!working.containsSession(working.remote)) {
            val keys = requireNotNull(bundle) { "需要联系人的预密钥" }
            require(id(keys.getString("accountId")) == peer.accountId && id(keys.getString("deviceId")) == peer.deviceId
                    && keys.getString("identityKey") == peer.identityKey) { "联系人设备身份已变化" }
            SessionBuilder(working, working.remote, working.local).process(decodeBundle(keys))
        }
        val body = json("id" to messageId, "sender" to me, "recipient" to peer.accountId, "text" to text)
        val encrypted = working.cipher.encrypt(body.toString().toByteArray())
        val framed = byteArrayOf(encrypted.type.toByte()) + encrypted.serialize()
        require(framed.size <= 65_535) { "密文超过信箱大小限制，请缩短文字" }
        return working.commit(this) to framed.b64()
    }

    fun open(me: String, peer: Contact, messageId: String, ciphertext: String): Pair<Protocol, String> {
        val working = Working(this, peer, me)
        val bytes = ciphertext.bytes64()
        require(bytes.size > 1)
        val body = bytes.copyOfRange(1, bytes.size)
        val decrypted = when (bytes.first().toInt()) {
            CiphertextMessage.PREKEY_TYPE -> working.cipher.decrypt(PreKeySignalMessage(body))
            CiphertextMessage.WHISPER_TYPE -> working.cipher.decrypt(SignalMessage(body))
            else -> throw IllegalArgumentException("未知的密文类型")
        }
        val content = JSONObject(decrypted.decodeToString(throwOnInvalidSequence = true))
        require(id(content.getString("id")) == messageId && id(content.getString("sender")) == peer.accountId &&
                id(content.getString("recipient")) == me) { "信封与正文标识不符" }
        val text = content.get("text") as String
        validateText(text)
        return working.commit(this) to text
    }
}

private fun decodeBundle(keys: JSONObject): PreKeyBundle {
    fun unpack(name: String) = JSONObject(keys.getString(name).bytes64().decodeToString())
    val ec = unpack("signedPrekey")
    val protocol = unpack("protocolPrekeys")
    val kem = protocol.getJSONObject("kyber")
    val one = keys.optJSONObject("oneTimePrekey")
    return PreKeyBundle(protocol.getInt("registrationId"), 1,
        one?.getInt("id") ?: PreKeyBundle.NULL_PRE_KEY_ID, one?.let { ECPublicKey(it.getString("material").bytes64()) },
        ec.getInt("id"), ECPublicKey(ec.getString("key").bytes64()), ec.getString("signature").bytes64(),
        IdentityKey(keys.getString("identityKey").bytes64()), kem.getInt("id"),
        KEMPublicKey(kem.getString("key").bytes64()), kem.getString("signature").bytes64())
}
