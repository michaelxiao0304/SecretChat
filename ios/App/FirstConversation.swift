// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

struct SharedInvitation {
    let code: String
    let server: URL

    private static let heading = "SecretChat 邀请"
    private static let serviceLabel = "服务地址："
    private static let codeLabel = "邀请码："

    var text: String { [Self.heading, Self.serviceLabel + server.absoluteString, Self.codeLabel + code].joined(separator: "\n") }

    static func parse(_ text: String) -> Self? {
        let lines = text.components(separatedBy: .newlines).filter { !$0.isEmpty }
        guard lines.count == 3, lines[0] == heading,
              lines[1].hasPrefix(serviceLabel), lines[2].hasPrefix(codeLabel),
              let server = try? Service.validatedBase(String(lines[1].dropFirst(serviceLabel.count))) else { return nil }
        let code = String(lines[2].dropFirst(codeLabel.count))
        guard validCode(code) else { return nil }
        return Self(code: code, server: server)
    }

    static func validCode(_ code: String) -> Bool {
        !code.isEmpty && !code.contains(where: { $0.isWhitespace })
    }
}

enum InvitationPasteResult { case accepted, invalid, differentService }

struct RegistrationServiceSettings: Equatable {
    var server: String
    var development = false

    private static let preset = Self(
        server: Bundle.main.object(forInfoDictionaryKey: "SecretChatServiceURL") as? String ?? "",
        development: Bundle.main.object(forInfoDictionaryKey: "SecretChatUnverifiedRegistration") as? String == "YES"
    )
    private static let key = "registration.serviceOverride." + preset.server

    private func normalized() throws -> Self {
        let text = server.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return Self(server: "", development: development) }
        guard !text.contains(where: { $0.isWhitespace }), !text.hasPrefix("/") else { throw ClientError.invalidServer }
        let address = text.contains(":/") ? text : "https://" + text
        let url = try Service.validatedBase(address)
        return Self(server: url.absoluteString, development: development)
    }

    static func load(from defaults: UserDefaults = .standard) -> Self {
        guard let values = defaults.dictionary(forKey: key), let server = values["server"] as? String,
              let development = values["development"] as? Bool else { return preset }
        let saved = Self(server: server, development: development)
        return (try? saved.normalized()) ?? preset
    }

    @discardableResult
    func save(to defaults: UserDefaults = .standard) throws -> Self {
        let value = try normalized()
        if value == Self.preset { defaults.removeObject(forKey: Self.key) }
        else { defaults.set(["server": value.server, "development": value.development], forKey: Self.key) }
        return value
    }
}

struct RegistrationDraft {
    var server: String { didSet { if server != oldValue { clearChallenge() } } }
    var email = "" { didSet { if email != oldValue { clearChallenge() } } }
    var development = false { didSet { if development != oldValue { clearChallenge() } } }
    private(set) var invitationServer: URL?
    var invite = "" { didSet { if invite != oldValue { invitationServer = nil; clearChallenge() } } }
    var code = ""
    private(set) var challenge: UUID?

    init(settings: RegistrationServiceSettings = .load()) {
        self.init(server: settings.server, development: settings.development)
    }

    init(server: String, development: Bool = false) {
        self.server = server
        self.development = development
    }

    var address: String { email.trimmingCharacters(in: .whitespacesAndNewlines) }
    var inviteCode: String { invite.trimmingCharacters(in: .whitespacesAndNewlines) }
    var serviceRoot: String {
        let configured = server.trimmingCharacters(in: .whitespacesAndNewlines)
        return configured.isEmpty ? invitationServer?.absoluteString ?? "" : configured
    }

    mutating func paste(_ input: String) -> InvitationPasteResult {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if let ticket = SharedInvitation.parse(text) {
            let configured = server.trimmingCharacters(in: .whitespacesAndNewlines)
            if !configured.isEmpty && !Self.sameRoot(configured, ticket.server) {
                return .differentService
            }
            clearChallenge()
            invite = ticket.code
            invitationServer = ticket.server
        } else {
            guard SharedInvitation.validCode(text) else { return .invalid }
            clearChallenge()
            invite = text
            invitationServer = nil
        }
        return .accepted
    }
    private static func sameRoot(_ value: String, _ invited: URL) -> Bool {
        let slash = CharacterSet(charactersIn: "/")
        return value.trimmingCharacters(in: slash) == invited.absoluteString.trimmingCharacters(in: slash)
    }

    var matchesInvitationService: Bool {
        invitationServer.map { Self.sameRoot(serviceRoot, $0) } ?? true
    }

    var hasService: Bool { (try? Service.validatedBase(serviceRoot)) != nil }
    var canRequestCode: Bool {
        hasService && matchesInvitationService && address.range(of: #"^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$"#, options: .regularExpression) != nil
    }
    var canRegister: Bool {
        guard SharedInvitation.validCode(inviteCode), canRequestCode else { return false }
        return development || (challenge != nil && code.utf8.count == 6 && code.utf8.allSatisfy { (48...57).contains($0) })
    }

    mutating func receiveChallenge(_ id: UUID) {
        code = ""
        challenge = id
    }

    private mutating func clearChallenge() {
        challenge = nil
        code = ""
    }
}

extension ContactAccess: LocalizedError {
    var errorDescription: String? { explanation }

    var title: String {
        switch self {
        case .available: return "身份未核验"
        case .blocked: return "已拉黑"
        case .identityChanged: return "设备身份已变化"
        case .missing: return "联系人已不在目录中"
        }
    }
    var explanation: String {
        switch self {
        case .available: return "短号用于识别联系人。首次建立会话后，本机会保存对方设备的身份绑定。"
        case .blocked: return "这位联系人已被拉黑，新消息和待发消息的重试均已暂停。"
        case .identityChanged: return "目录中的设备标识或身份公钥与本机保存的记录不一致。新消息和待发重试已暂停。请通过电话或当面确认对方是否更换了设备。"
        case .missing: return "当前联系人目录已没有这条记录。新消息和待发重试已暂停，可返回联系人刷新目录。"
        }
    }
}

extension Delivery {
    var label: String {
        switch self {
        case .pending: return "本机待提交"
        case .accepted: return "服务端已接收"
        case .received: return "对方设备已收到"
        case .expired: return "已过期，未获设备确认"
        case .unconfirmed: return "投递结果未确认"
        case .unreadable: return "无法解密"
        }
    }

    var symbol: String {
        switch self {
        case .pending: return "clock"
        case .accepted: return "checkmark"
        case .received: return "checkmark.circle"
        case .expired: return "clock.badge.exclamationmark"
        case .unconfirmed: return "questionmark.circle"
        case .unreadable: return "lock.trianglebadge.exclamationmark"
        }
    }

    func explanation(access: ContactAccess) -> String {
        switch self {
        case .pending:
            return access == .available ? "已保存在本机，下次同步时会尝试提交。" : "已保存在本机，目前暂停重试。"
        case .accepted: return "服务端已接受密文，尚未取得对方设备的收件确认。"
        case .received: return "对方设备已确认收件，阅读情况未知。"
        case .expired: return "服务端保存期限已过，本机没有收到对方设备的收件确认。"
        case .unconfirmed: return "本机无法确认这条消息的最终投递结果。"
        case .unreadable: return "此设备无法解密这条消息。"
        }
    }
}
