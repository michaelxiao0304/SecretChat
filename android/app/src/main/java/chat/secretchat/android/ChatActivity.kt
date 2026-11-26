// SPDX-License-Identifier: AGPL-3.0-only
package chat.secretchat.android

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import javax.net.ssl.SSLException

class ChatActivity : ComponentActivity() {
    private val app get() = application as ChatApp
    private val main = Handler(Looper.getMainLooper())
    private val ink = Color.rgb(26, 34, 53)
    private val blue = Color.rgb(25, 71, 194)
    private val paper = Color.rgb(251, 251, 247)
    private val faint = Color.rgb(232, 239, 255)
    private lateinit var column: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var feedback: TextView
    private var device: Device? = null
    private var selected: Contact? = null
    private var active = false
    private var syncing = false
    private var again = false
    private var busy = false
    private var socket: WebSocket? = null
    private var receiverEmail: String? = null
    private var challengeId: String? = null
    private var composer: EditText? = null
    private var timeline: ScrollView? = null
    private var sendButton: Button? = null
    private val refresh = Runnable { if (active) { connect(); sync() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, backToContacts)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        page("SecretChat")
        status("正在打开本机消息库…")
        work({ app.device }) { opened ->
            device = opened
            if (opened.state.account == null) registration() else {
                contacts()
                if (active) { connect(); sync() }
            }
        }
    }

    private val backToContacts = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = contacts()
    }

    override fun onStart() {
        super.onStart()
        active = true
        if (device?.state?.account != null) { connect(); sync() }
    }
    override fun onStop() {
        active = false
        main.removeCallbacks(refresh)
        socket?.cancel()
        socket = null
        super.onStop()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun label(value: String, size: Int = 16, color: Int = ink) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun stack() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(blue)
        setOnClickListener { action() }
    }
    private fun field(hintText: String, multiline: Boolean = false) = EditText(this).apply {
        hint = hintText
        textSize = 16f
        setTextColor(ink)
        setSingleLine(!multiline)
        if (multiline) maxLines = 4
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSaveEnabled = false
    }
    private fun page(title: String, showLetter: Boolean = false) {
        composer = null; sendButton = null; timeline = null
        column = stack().apply {
            setBackgroundColor(paper)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(dp(20) + insets.systemWindowInsetLeft, dp(18) + insets.systemWindowInsetTop,
                    dp(20) + insets.systemWindowInsetRight, dp(12) + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(column)
        val heading = row()
        heading.addView(label(title, 30).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (showLetter) heading.addView(ImageView(this).apply {
            setImageResource(R.drawable.invitation_letter)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(88), dp(64)))
        column.addView(heading)
        feedback = label("", 14, blue)
        column.addView(feedback)
        feedback.visibility = View.GONE
        list = stack()
    }

    private fun status(value: String) {
        feedback.text = value
        feedback.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun errorText(error: Exception) = when (error) {
        is HttpFailure -> error.message.orEmpty()
        is SSLException -> "无法验证服务的 HTTPS 连接，请核对地址和证书"
        is IOException -> "连接中断，请检查网络后重试"
        is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf {
            it.any { c -> c.code in 0x4e00..0x9fff }
        } ?: "无法验证服务数据或本机状态"
        else -> "操作未完成，请重试"
    }
    private fun <T> work(job: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true
        app.worker.execute {
            val result = try { Result.success(job()) } catch (e: Exception) { Result.failure(e) }
            main.post {
                busy = false
                if (!isDestroyed && !isFinishing) result.fold(success) { status(errorText(it as Exception)) }
            }
        }
    }

    private fun registration() {
        page("凭邀请加入", showLetter = true)
        val scroll = ScrollView(this)
        column.addView(scroll)
        scroll.addView(list)
        val prefs = getSharedPreferences("service", MODE_PRIVATE)
        val server = field("https://…/secretchat").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("root", if (BuildConfig.DEBUG) "https://101.132.60.65/secretchat" else ""))
        }
        val invite = field("邀请码或完整邀请", true)
        val email = field("邮箱").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val code = field("六位邮箱验证码").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val dev = CheckBox(this).apply {
            text = "联调服务免验证码"
            isChecked = BuildConfig.DEBUG && prefs.getBoolean("development", true)
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        }
        list.addView(label("邀请", 14, blue)); list.addView(invite)
        list.addView(label("邮箱", 14, blue)); list.addView(email)
        fun ticket() = Invitation.parse(invite.text.toString(), server.text.toString())
        fun address() = email.text.toString().trim().also { require(android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches()) { "请输入有效的邮箱" } }
        val challenge = button("获取验证码") {
            try {
                val chosen = ticket()
                val mail = address()
                work({ Service(chosen.root).challenge(mail) }) {
                    receiverEmail = chosen.root + "\n" + mail
                    challengeId = it
                    code.visibility = View.VISIBLE
                    status("验证码已发送")
                }
            } catch (e: Exception) { status(errorText(e)) }
        }
        list.addView(challenge); list.addView(code)
        fun updateMode() {
            receiverEmail = null; challengeId = null
            challenge.visibility = if (dev.isChecked) View.GONE else View.VISIBLE
            code.visibility = View.GONE
        }
        dev.setOnCheckedChangeListener { _, _ -> updateMode() }
        updateMode()
        list.addView(button("注册") {
            try {
                val chosen = ticket()
                val mail = address()
                val skip = BuildConfig.DEBUG && dev.isChecked
                val verification = code.text.toString()
                val proof = if (skip) null else {
                    require(receiverEmail == chosen.root + "\n" + mail && verification.matches(Regex("[0-9]{6}"))) { "请先获取并填写当前邮箱的验证码" }
                    requireNotNull(challengeId)
                }
                val engine = requireNotNull(device)
                work({
                    val account = Service(chosen.root).register(mail, chosen.code, proof, verification, engine.state.protocol)
                    engine.commit(engine.state.copy(account = account, root = chosen.root))
                }) {
                    prefs.edit().putString("root", chosen.root).putBoolean("development", skip).apply()
                    contacts(); connect(); sync()
                }
            } catch (e: Exception) { status(errorText(e)) }
        })
        val settings = stack().apply {
            visibility = View.GONE
            addView(label("HTTPS 服务地址", 14, blue))
            addView(server)
            addView(dev)
        }
        list.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "服务设置"
            setOnClickListener { settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        list.addView(settings)
    }

    private fun api(): Service {
        val state = requireNotNull(device).state
        return Service(requireNotNull(state.root), requireNotNull(state.account).token)
    }

    private fun AlertDialog.Builder.showSecure() {
        val dialog = create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun contacts() {
        selected = null
        backToContacts.isEnabled = false
        page("联系人")
        val actions = row()
        actions.addView(button("签发邀请") {
            work({ api().invite() }) { ticket ->
                AlertDialog.Builder(this).setTitle("单次邀请").setMessage(ticket.sharedText())
                    .setNegativeButton("关闭", null).setPositiveButton("分享") { _, _ ->
                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ticket.sharedText())
                        startActivity(Intent.createChooser(intent, "分享邀请"))
                    }.showSecure()
            }
        })
        actions.addView(button("兑换邀请") {
            val input = field("邀请码或完整邀请", true)
            AlertDialog.Builder(this).setTitle("添加联系人").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("兑换") { _, _ ->
                    val value = input.text.toString()
                    work({
                        val ticket = Invitation.parse(value, requireNotNull(device?.state?.root))
                        api().redeem(ticket.code)
                    }) { sync() }
                }.showSecure()
        })
        actions.addView(button("刷新") { connect(); sync() })
        column.addView(actions)
        val scroll = ScrollView(this)
        scroll.addView(list); column.addView(scroll)
        renderList()
    }

    private fun chat(peer: Contact) {
        selected = peer
        backToContacts.isEnabled = true
        page("与 " + peer.accountId.take(8) + " 聊天")
        val actions = row()
        actions.addView(button("联系人") { contacts() })
        actions.addView(button("同步") { sync() }); column.addView(actions)
        val scroll = ScrollView(this).apply { addView(list) }
        timeline = scroll
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val entry = row()
        val input = field("输入消息", true)
        composer = input
        entry.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = button("发送") {
            val text = input.text.toString()
            work({ requireNotNull(device).queue(peer, text, api()) }) {
                if (composer === input && input.text.toString() == text) input.text.clear()
                renderList(); sync()
            }
        }
        sendButton = send
        entry.addView(send); column.addView(entry)
        renderList()
    }

    private fun renderList() {
        val state = device?.state ?: return
        val peer = selected
        val scroll = timeline
        val nearBottom = scroll == null || !scroll.canScrollVertically(1)
        list.removeAllViews()
        if (peer == null) {
            if (state.contacts.isEmpty()) list.addView(label("还没有联系人。可签发邀请，或兑换对方的邀请。"))
            for (contact in state.contacts) {
                val item = button(contact.accountId.take(8) + "  ·  " + (state.restriction(contact) ?: "身份未核验")) { chat(contact) }
                item.contentDescription = "打开与 " + contact.accountId + " 的对话"
                list.addView(item)
            }
        } else {
            val reason = state.restriction(peer)
            sendButton?.isEnabled = reason == null
            list.addView(label(reason ?: "端到端加密 · 身份未核验", 14, blue))
            for (message in state.messages.filter { it.peer == peer.accountId }) {
                val bubble = stack().apply {
                    background = rounded(if (message.outgoing) faint else Color.WHITE)
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    addView(label(message.text ?: "无法解密"))
                    if (message.outgoing) addView(label(message.status.label, 12, blue))
                }
                list.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = if (message.outgoing) Gravity.END else Gravity.START
                    setMargins(if (message.outgoing) dp(32) else 0, dp(5), if (message.outgoing) 0 else dp(32), dp(5))
                })
            }
            if (nearBottom) scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun sync() {
        val engine = device ?: return
        if (!active || engine.state.account == null) return
        if (syncing) { again = true; return }
        syncing = true
        main.removeCallbacks(refresh)
        val before = engine.state
        app.worker.execute {
            val failure = try { engine.sync(api()); null } catch (e: Exception) { e }
            main.post {
                syncing = false
                if (!active || isDestroyed) return@post
                if (engine.state !== before) renderList()
                status(failure?.let(::errorText) ?: "")
                if (again) { again = false; sync() } else main.postDelayed(refresh, 30_000)
            }
        }
    }

    private fun connect() {
        if (!active || socket != null) return
        socket = api().listen(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val hint = try { JSONObject(text).optString("type") } catch (_: Exception) { return }
                if (hint in listOf("ready", "available", "outcome")) main.post { if (socket === webSocket) sync() }
            }
            private fun disconnected(ws: WebSocket) = main.post {
                if (socket === ws) {
                    socket = null
                    if (active) { main.removeCallbacks(refresh); main.postDelayed(refresh, 3_000) }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected(webSocket) }
        })
    }
}
