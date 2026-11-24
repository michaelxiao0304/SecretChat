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
    @State private var settings = false
    @State private var editedSettings = RegistrationServiceSettings(server: "")
    @State private var settingsError: String?
    @State private var pasteNote: String?
    @FocusState private var editingInvite: Bool

    private var hideInvite: Bool { !editingInvite && !draft.invite.isEmpty }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                invitation
                registration
            }
            .frame(maxWidth: 540, alignment: .leading)
            .padding(.horizontal, 24).padding(.vertical, 20)
            .frame(maxWidth: .infinity)
        }
        .background(InvitationStyle.paper)
        .scrollDismissesKeyboard(.interactively)
        .disabled(model.busy)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("开发联调", systemImage: "slider.horizontal.3") {
                    Diagnostics.event(.account, "registration.settings", .ok)
                    editedSettings = RegistrationServiceSettings(server: draft.server, development: draft.development)
                    settingsError = nil
                    settings = true
                }.disabled(model.busy)
            }
        }
        .sheet(isPresented: $settings) {
            NavigationStack {
                Form {
                    Section {
                        TextField("HTTPS 服务根地址（含部署前缀）", text: $editedSettings.server)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .onChange(of: editedSettings.server) { settingsError = nil }
                        Toggle("服务已开启免验证码联调", isOn: $editedSettings.development)
                    } footer: {
                        if let settingsError { Text(settingsError).foregroundStyle(.red) }
                        else { Text("仅在测试服务已开启免验证码注册时打开。") }
                    }
                }
                .navigationTitle("开发联调").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") {
                            do {
                                let saved = try editedSettings.save()
                                draft.server = saved.server
                                draft.development = saved.development
                                Diagnostics.event(.account, "registration.settings.saved", .ok)
                                settings = false
                            } catch {
                                settingsError = "请填写 HTTPS 主机和路径，例如 example.com/secretchat。地址中不能包含空格、账号或查询参数。"
                                Diagnostics.failure(.account, "registration.settings.saved", error)
                            }
                        }
                    }
                }
            }.protectedSurface()
        }
    }

    private var invitation: some View {
        Group {
            HStack(spacing: 12) {
                Text("凭邀请加入").font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                if let letter = InvitationStyle.letter {
                    Image(uiImage: letter).resizable().scaledToFit()
                        .frame(width: 92).clipShape(RoundedRectangle(cornerRadius: 8))
                        .accessibilityHidden(true)
                }
            }
            Text("粘贴朋友分享的邀请，或手动输入邀请码，然后填写邮箱。")
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
                TextField("输入或粘贴邀请码", text: $draft.invite)
                    .focused($editingInvite)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .font(.body.monospaced()).frame(minHeight: 44)
                    .foregroundStyle(hideInvite ? Color.clear : Color.primary)
                    .overlay(alignment: .leading) {
                        if hideInvite { Text("••••••••").allowsHitTesting(false).accessibilityHidden(true) }
                    }
                    .accessibilityValue(hideInvite ? "已填写邀请码" : draft.invite)
                    .accessibilityIdentifier("invite.code")
                    .privacySensitive()
                Divider()
            }
            if let pasteNote { Text(pasteNote).font(.footnote).foregroundStyle(.secondary) }
        }
    }

    private var registration: some View {
        Group {
            VStack(alignment: .leading, spacing: 6) {
                Text("邮箱").font(.headline)
                TextField("name@example.com", text: $draft.email)
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
            }
            if !draft.matchesInvitationService {
                Text("邀请与当前服务不匹配。请在「开发联调」核对，或换用当前服务签发的邀请。")
                    .font(.footnote).foregroundStyle(InvitationStyle.ink)
            }
            if !draft.hasService && !draft.inviteCode.isEmpty {
                Text("请粘贴朋友分享的完整邀请，或在「开发联调」选择服务。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if !draft.development {
                Button(draft.challenge == nil ? "获取邮箱验证码" : "重新获取验证码") {
                    Task { await model.perform {
                        let id = try await model.requestCode(server: draft.serviceRoot, email: draft.address)
                        draft.receiveChallenge(id)
                    } }
                }.disabled(!draft.canRequestCode)
                if draft.challenge != nil {
                    TextField("六位邮箱验证码", text: $draft.code)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .textContentType(.oneTimeCode).textFieldStyle(.roundedBorder)
                }
            }
            Button("注册") {
                Task { await model.perform {
                    try await model.register(server: draft.serviceRoot, email: draft.address, invite: draft.inviteCode,
                                             challenge: draft.challenge, code: draft.code)
                } }
            }.buttonStyle(InvitationAction()).disabled(!draft.canRegister)
            if model.busy { ProgressView("正在联系服务…").font(.footnote) }
        }
    }

}
