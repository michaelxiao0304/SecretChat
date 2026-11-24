// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

enum InvitationStyle {
    static let letter = Bundle.main.url(forResource: "InvitationLetter", withExtension: "png")
        .flatMap { UIImage(contentsOfFile: $0.path) }
    static let blue = Color(red: 0.1, green: 0.28, blue: 0.76)
    static let ink = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(red: 0.57, green: 0.74, blue: 1, alpha: 1)
            : UIColor(red: 0.09, green: 0.25, blue: 0.61, alpha: 1)
    })
    static let paper = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? .systemBackground : UIColor(red: 0.985, green: 0.985, blue: 0.97, alpha: 1)
    })
}

struct InvitationAction: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.headline).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 48).padding(.horizontal, 12)
            .foregroundStyle(.white)
            .background(enabled ? InvitationStyle.blue : Color(uiColor: .systemGray), in: RoundedRectangle(cornerRadius: 12))
            .opacity(configuration.isPressed ? 0.82 : 1)
    }
}

struct RegistrationScreen: View {
    @ObservedObject var model: DeviceModel
    @State private var draft = RegistrationDraft()
    @State private var opened = false
    @State private var settings = false
    @State private var pasteNote: String?
    // Keep the setup field on screen while the user is typing a previously missing URL.
    @State private var needsAddress = !RegistrationDraft().hasService

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if opened { registration } else { invitation }
            }
            .frame(maxWidth: 540, alignment: .leading)
            .padding(.horizontal, 24).padding(.vertical, 20)
            .frame(maxWidth: .infinity)
        }
        .onChange(of: draft.hasService) { if !draft.hasService { needsAddress = true } }
        .background(InvitationStyle.paper)
        .scrollDismissesKeyboard(.interactively)
        .disabled(model.busy)
        .toolbar {
            if opened {
                ToolbarItem(placement: .topBarLeading) {
                    Button("邀请", systemImage: "chevron.backward") { opened = false }
                        .disabled(model.busy)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("开发联调", systemImage: "slider.horizontal.3") {
                    Diagnostics.event(.account, "registration.settings", .ok)
                    settings = true
                }.disabled(model.busy)
            }
        }
        .sheet(isPresented: $settings, onDismiss: { needsAddress = !draft.hasService }) {
            NavigationStack {
                Form {
                    Section {
                        addressField
                        Toggle("服务已开启免验证码联调", isOn: $draft.development)
                    } footer: { Text("仅在测试服务已开启免验证码注册时打开。") }
                }
                .navigationTitle("开发联调").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("完成") { settings = false } }
            }.protectedSurface()
        }
    }

    private var invitation: some View {
        Group {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(Color(red: 0.86, green: 0.92, blue: 0.94))
                if let letter = InvitationStyle.letter {
                    Image(uiImage: letter).resizable().scaledToFit()
                } else {
                    Image(systemName: "envelope.open.fill").font(.largeTitle).foregroundStyle(InvitationStyle.blue)
                }
            }
            .aspectRatio(322.0 / 235.0, contentMode: .fit).accessibilityHidden(true)
            Text("凭邀请加入").font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
            Text("粘贴朋友分享的邀请，或输入一次性邀请码，再验证你的邮箱。")
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 8) {
                Divider()
                HStack {
                    Text("一次性邀请码").font(.subheadline.weight(.semibold))
                    Spacer()
                    PasteButton(payloadType: String.self) { values in
                        guard let first = values.first else { return }
                        switch draft.paste(first) {
                        case .accepted: pasteNote = nil
                        case .invalid: pasteNote = "请复制完整邀请或单独的邀请码。"
                        case .differentService: pasteNote = "此邀请来自其他服务，请核对邀请人和开发联调中的服务地址。"
                        }
                    }.controlSize(.small)
                }
                SecureField("输入或粘贴邀请码", text: $draft.invite)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .font(.body.monospaced()).frame(minHeight: 44)
                    .accessibilityIdentifier("invite.code")
                Divider()
            }
            if let pasteNote { Text(pasteNote).font(.footnote).foregroundStyle(.secondary) }
            Button("展开邀请") {
                needsAddress = !draft.hasService
                Diagnostics.event(.account, "invitation.open", .ok)
                opened = true
            }
            .buttonStyle(InvitationAction())
        }
    }

    private var registration: some View {
        Group {
            Label("一次性邀请", systemImage: "envelope.open").foregroundStyle(InvitationStyle.ink)
            Text(draft.development ? "注册这台设备" : "验证你的邮箱")
                .font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
            Text(draft.development ? "此测试服务将使用邮箱和邀请码注册。" : "验证码会发到这个邮箱。")
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 6) {
                Text("一次性邀请码").font(.headline)
                SecureField("输入或粘贴邀请码", text: $draft.invite)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder)
            }
            if !needsAddress && draft.hasService {
                LabeledContent("邀请服务") {
                    Text(draft.serviceRoot).multilineTextAlignment(.trailing)
                }.font(.footnote).foregroundStyle(.secondary)
            }
            if !draft.matchesInvitationService {
                Text("这张邀请属于其他服务。请恢复原服务地址，或替换为当前服务签发的邀请码。")
                    .font(.footnote).foregroundStyle(InvitationStyle.ink)
            }
            if needsAddress || !draft.hasService {
                VStack(alignment: .leading, spacing: 6) {
                    Text("邀请服务地址").font(.headline)
                    addressField.textFieldStyle(.roundedBorder)
                    Text("此安装包未预设服务。请填写邀请人提供的 HTTPS 服务根地址。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                Text("邮箱").font(.headline)
                TextField("name@example.com", text: $draft.email)
                    .textContentType(.emailAddress).keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
            }
            if !draft.development {
                Button(draft.challenge == nil ? "获取邮箱验证码" : "重新获取验证码") {
                    Task { await model.perform {
                        let id = try await model.requestCode(server: draft.serviceRoot, email: draft.address)
                        draft.receiveChallenge(id)
                    } }
                }.disabled(!draft.canRequestCode)
                if draft.challenge != nil {
                    TextField("六位邮箱验证码", text: $draft.code).keyboardType(.numberPad)
                        .textContentType(.oneTimeCode).textFieldStyle(.roundedBorder)
                }
            }
            Button("注册并打开联系人") {
                Task { await model.perform {
                    try await model.register(server: draft.serviceRoot, email: draft.address, invite: draft.inviteCode,
                                             challenge: draft.challenge, code: draft.code)
                } }
            }.buttonStyle(InvitationAction()).disabled(!draft.canRegister)
            if model.busy { ProgressView("正在联系服务…").font(.footnote) }
        }
    }

    private var addressField: some View {
        TextField("HTTPS 服务根地址（含部署前缀）", text: $draft.server)
            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
    }
}
