// SPDX-License-Identifier: AGPL-3.0-only
package chat.secretchat.android

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class Account(val accountId: String, val deviceId: String, val token: String)
internal data class Contact(val accountId: String, val deviceId: String, val identityKey: String, val blocked: Boolean)
internal data class Outgoing(val messageId: String, val recipientAccountId: String, val recipientDeviceId: String,
                             val expiresAtMs: Long, val ciphertext: String)
internal data class Envelope(val sequence: Long, val reference: String, val messageId: String, val senderAccountId: String,
                             val senderDeviceId: String, val expiresAtMs: Long, val ciphertext: String)
internal data class Page(val envelopes: List<Envelope>, val through: Long, val nextAfter: Long, val done: Boolean) {
    fun validate(after: Long, boundary: Long?) {
        require(through >= after && (boundary == null || boundary == through))
        require(if (done) nextAfter == through else nextAfter > after && nextAfter < through)
        var previous = after
        for (item in envelopes) {
            require(item.sequence > previous && item.sequence <= nextAfter)
            previous = item.sequence
        }
        require(done || previous == nextAfter)
    }
}
internal data class Invitation(val root: String, val code: String) {
    fun sharedText() = "SecretChat 邀请\n服务地址：" + root + "\n邀请码：" + code
    companion object {
        fun parse(input: String, configured: String): Invitation {
            val lines = input.trim().lines()
            val (root, code) = if (lines.size == 3 && lines[0] == "SecretChat 邀请" &&
                lines[1].startsWith("服务地址：") && lines[2].startsWith("邀请码：")) {
                val root = serviceRoot(lines[1].removePrefix("服务地址："))
                require(configured.isBlank() || serviceRoot(configured) == root) { "邀请的服务地址与当前配置不同，请先核对" }
                root to lines[2].removePrefix("邀请码：")
            } else serviceRoot(configured) to input.trim()
            require(code.isNotEmpty() && code.none { it.isWhitespace() || it.isISOControl() }) { "请输入有效的邀请码或完整邀请" }
            return Invitation(root, code)
        }
    }
}

internal fun serviceRoot(input: String): String {
    val message = "请输入有效的 HTTPS 服务地址"
    val uri = try { URI(input.trim()) } catch (_: Exception) { throw IllegalArgumentException(message) }
    require(uri.scheme.equals("https", true) && uri.host != null && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && !uri.rawPath.contains('\\')) { message }
    return uri.toString().toHttpUrl().toString().trimEnd('/')
}

internal class HttpFailure(val status: Int, requestId: String?) : IOException(
    "服务请求失败（HTTP " + status + "）" +
        (requestId?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) }?.let { " · 请求编号 " + it } ?: "")
)

internal interface Mailbox {
    fun contacts(): List<Contact>
    fun bundle(peer: String): JSONObject
    fun remaining(): Int
    fun upload(keys: List<OneTime>)
    fun page(after: Long, through: Long?): Page
    fun ack(reference: String)
    fun submit(item: Outgoing): String
    fun outcome(reference: String): Delivery
}

internal class Service(root: String, private val token: String? = null, private val client: OkHttpClient = http) : Mailbox {
    private val base = serviceRoot(root)
    companion object {
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).pingInterval(25, TimeUnit.SECONDS).build()
    }

    private fun request(path: String) = Request.Builder().url(base + "/" + path).apply {
        token?.let { header("Authorization", "Bearer " + it) }
    }

    private fun exchange(path: String, method: String = "GET", expected: Int = 200, payload: JSONObject? = null): String {
        val body = if (method == "POST") (payload?.toString() ?: "").toRequestBody("application/json".toMediaType()) else null
        val request = request(path).method(method, body).build()
        return client.newCall(request).execute().use {
            if (it.code != expected) throw HttpFailure(it.code, it.header("X-Request-ID"))
            it.body?.string().orEmpty()
        }
    }

    fun challenge(email: String) = id(JSONObject(exchange("api/challenges", "POST", payload =
        json("email" to email, "purpose" to "register"))).getString("challengeId"))

    fun register(email: String, invite: String, challenge: String?, code: String, keys: Protocol): Account {
        val body = keys.publicBundle().put("inviteCode", invite)
        if (challenge == null) body.put("email", email) else body.put("challengeId", challenge).put("code", code)
        val result = JSONObject(exchange("api/accounts", "POST", 201, body))
        return Account(id(result.getString("accountId")), id(result.getString("deviceId")),
            result.getString("token").also { require(it.isNotBlank()) })
    }

    fun invite() = Invitation(base, JSONObject(exchange("api/invites", "POST")).getString("code"))
    fun redeem(code: String) { exchange("api/invites/redeem", "POST", 204, json("code" to code)) }

    override fun contacts(): List<Contact> {
        val rows = JSONArray(exchange("api/contacts"))
        return (0 until rows.length()).map { rows.getJSONObject(it) }.map {
            Contact(id(it.getString("accountId")), id(it.getString("deviceId")), it.getString("identityKey"), it.getBoolean("blocked"))
        }
    }

    override fun bundle(peer: String) = JSONObject(exchange("api/contacts/" + id(peer) + "/prekeys", "POST"))
    override fun remaining() = JSONObject(exchange("api/keys/one-time")).getInt("remaining")
    override fun upload(keys: List<OneTime>) {
        val data = JSONArray().apply { keys.forEach { put(json("id" to it.id, "material" to it.material)) } }
        require(JSONObject(exchange("api/keys/one-time", "POST", payload = json("keys" to data))).getInt("remaining") >= 0)
    }

    override fun page(after: Long, through: Long?): Page {
        val path = "api/mailbox/envelopes?after=" + after + "&limit=50" + (through?.let { "&through=" + it } ?: "")
        val page = JSONObject(exchange(path))
        val rows = page.getJSONArray("envelopes")
        val items = (0 until rows.length()).map { rows.getJSONObject(it) }.map {
            Envelope(it.getLong("sequence"), id(it.getString("reference")), id(it.getString("messageId")),
                id(it.getString("senderAccountId")), id(it.getString("senderDeviceId")),
                it.getLong("expiresAtMs"), it.getString("ciphertext"))
        }
        return Page(items, page.getLong("through"), page.getLong("nextAfter"), page.getBoolean("done"))
    }
    override fun ack(reference: String) { exchange("api/mailbox/envelopes/" + id(reference) + "/ack", "POST", 204) }

    override fun submit(item: Outgoing): String {
        val data = json("messageId" to item.messageId, "recipientAccountId" to item.recipientAccountId,
            "recipientDeviceId" to item.recipientDeviceId, "expiresAtMs" to item.expiresAtMs, "ciphertext" to item.ciphertext)
        val receipt = JSONObject(exchange("api/mailbox/envelopes", "POST", 202, data))
        require(receipt.getLong("expiresAtMs") == item.expiresAtMs)
        return id(receipt.getString("reference"))
    }

    override fun outcome(reference: String): Delivery {
        val result = JSONObject(exchange("api/mailbox/outcomes/" + id(reference)))
        require(id(result.getString("reference")) == reference)
        return when (result.getString("status")) {
            "accepted" -> Delivery.ACCEPTED
            "received" -> Delivery.RECEIVED
            "expired" -> Delivery.EXPIRED
            else -> throw IOException("未知的投递状态")
        }
    }

    fun listen(listener: WebSocketListener) = client.newWebSocket(request("ws").build(), listener)
}
