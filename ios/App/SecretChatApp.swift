// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import UserNotifications

@main
struct SecretChatApp: App {
    @UIApplicationDelegateAdaptor(PushDelegate.self) private var push
    @StateObject private var model = DeviceModel()
    @Environment(\.scenePhase) private var phase
    @Environment(\.isSceneCaptured) private var captured

    var body: some Scene {
        WindowGroup {
            RootScreen(model: model)
                .overlay { if phase != .active || captured { Color(uiColor: .systemBackground).ignoresSafeArea() } }
                .task { model.open() }
                .onChange(of: phase) {
                    switch phase {
                    case .active: Diagnostics.event(.lifecycle, "scene.foreground", .ok)
                    case .background: Diagnostics.event(.lifecycle, "scene.background", .ok)
                    default: break
                    }
                }
                .task(id: phase == .active ? model.profile?.account?.deviceId : nil) {
                    guard phase == .active, model.profile?.account != nil else { return }
                    if await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .authorized {
                        UIApplication.shared.registerForRemoteNotifications()
                    }
                    await model.listen()
                }
                .onReceive(push.$token.compactMap { $0 }) { value in Task { await model.tokenChanged(value) } }
        }
    }
}

private struct RootScreen: View {
    @ObservedObject var model: DeviceModel

    var body: some View {
        NavigationStack {
            Group {
                if model.profile?.account != nil { ContactsScreen(model: model) }
                else if model.profile != nil { RegistrationScreen(model: model) }
                else if model.issue != nil {
                    ContentUnavailableView {
                        Label("无法打开本机数据", systemImage: "lock")
                    } actions: { Button("重试") { model.open() } }
                } else { ProgressView() }
            }
            .navigationTitle(model.profile?.account == nil ? "SecretChat" : "联系人")
            .safeAreaInset(edge: .bottom) {
                if let issue = model.issue {
                    Text(issue).font(.footnote).frame(maxWidth: .infinity).padding(8).background(.regularMaterial)
                }
            }
        }
        .privacySensitive()
    }
}

private struct RegistrationScreen: View {
    @ObservedObject var model: DeviceModel
    @State private var server = ""
    @State private var email = ""
    @State private var invite = ""
    @State private var challenge: UUID?
    @State private var code = ""
    @State private var development = false

    var body: some View {
        Form {
            Section("开发服务") {
                TextField("HTTPS 服务根地址（含 /secretchat）", text: $server)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                Toggle("服务已开启免验证码联调", isOn: $development)
            }
            Section("注册设备") {
                TextField("邮箱", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("邀请码", text: $invite)
                if !development {
                    Button("获取邮箱验证码") {
                        Task { await model.perform { challenge = try await Service(base: server).challenge(email: email) } }
                    }.disabled(email.isEmpty || server.isEmpty)
                    TextField("六位验证码", text: $code).keyboardType(.numberPad)
                }
                Button("注册") {
                    Task { await model.perform {
                        try await model.register(server: server, email: email, invite: invite,
                                                 challenge: development ? nil : challenge, code: code)
                    } }
                }.disabled(server.isEmpty || email.isEmpty || invite.isEmpty || (!development && (challenge == nil || code.count != 6)))
            }
        }
        .disabled(model.busy)
        .onChange(of: server) { challenge = nil }
        .onChange(of: email) { challenge = nil }
    }
}

private struct ContactsScreen: View {
    @ObservedObject var model: DeviceModel
    @State private var invite: String?
    @State private var notificationNote: String?

    var body: some View {
        List {
            Section {
                if model.profile?.contacts.isEmpty == true { Text("注册另一台设备后，下拉刷新联系人。") }
                ForEach(model.profile?.contacts ?? []) { peer in
                    NavigationLink {
                        ConversationScreen(model: model, id: peer.id)
                    } label: {
                        VStack(alignment: .leading) {
                            Text(String(peer.id.wire.prefix(8))).monospaced()
                            Text(peer.blocked ? "已拉黑" : model.profile?.allows(peer) == true ? "身份未核验" : "设备身份已变化")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Button("生成单次邀请码") { Task { await model.perform { invite = try await model.service().invite() } } }
                    .disabled(model.busy)
                if let invite { ShareLink(item: invite) { Label("分享邀请码", systemImage: "square.and.arrow.up") } }
            }
            Section("通知") {
                Button("启用新消息通知") {
                    Task { await model.perform {
                        let allowed = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
                        Diagnostics.event(.push, "apns.permission", allowed ? .ok : .skip)
                        if allowed { UIApplication.shared.registerForRemoteNotifications() }
                        notificationNote = allowed ? "已允许通知" : "通知权限已关闭，可在系统设置中更改。"
                    } }
                }
                if let notificationNote { Text(notificationNote).font(.caption) }
                Toggle("在通知中显示正文", isOn: Binding(get: { model.profile?.settings.showBody == true },
                    set: { value in Task { await model.setPreview(value) } })).disabled(model.busy)
            }
        }
        .refreshable { await model.refresh() }
    }
}

private struct ConversationScreen: View {
    @ObservedObject var model: DeviceModel
    let id: UUID
    @State private var draft = ""

    var body: some View {
        let peer = model.profile?.contacts.first(where: { $0.id == id })
        VStack {
            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach((model.profile?.messages ?? []).filter { $0.peer == id }, id: \.selfKey) { message in
                        VStack(alignment: message.outgoing ? .trailing : .leading, spacing: 3) {
                            Text(message.text ?? "无法解密").padding(10)
                                .background(message.outgoing ? Color.accentColor.opacity(0.15) : Color.secondary.opacity(0.12),
                                            in: RoundedRectangle(cornerRadius: 12))
                            if message.outgoing { Text(message.status.label).font(.caption2).foregroundStyle(.secondary) }
                        }.frame(maxWidth: .infinity, alignment: message.outgoing ? .trailing : .leading)
                    }
                }.padding()
            }
            if let peer, model.profile?.allows(peer) == true {
                HStack {
                    TextField("文字消息", text: $draft, axis: .vertical).textFieldStyle(.roundedBorder)
                    Button("发送") { Task { await model.perform { try await model.send(draft, to: peer); draft = "" } } }
                        .disabled(model.busy || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }.padding()
            } else { Text("联系受限，发送已暂停。").foregroundStyle(.secondary) }
        }
        .navigationTitle(String(id.wire.prefix(8)))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private extension ChatMessage {
    var selfKey: String { (outgoing ? "out-" : "in-") + id.wire }
}

private extension Delivery {
    var label: String {
        switch self {
        case .pending: return "待提交"
        case .accepted: return "服务端已接收"
        case .received: return "对方设备已收到"
        case .expired: return "已过期未送达"
        case .unconfirmed: return "投递结果未确认"
        case .unreadable: return "无法解密"
        }
    }
}
