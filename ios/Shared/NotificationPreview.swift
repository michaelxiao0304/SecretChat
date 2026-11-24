// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import UserNotifications

enum NotificationPreview {
    static func render(reference: UUID, load: () throws -> Profile?,
                       fetch: (URL, Account, UUID) async throws -> Incoming) async -> UNNotificationContent {
        let fallback = content(sender: nil, text: nil, showBody: false)
        do {
            guard let initial = try load(), let base = initial.server, let account = initial.account else {
                Diagnostics.event(.push, "preview.profile", .fallback, reason: .unavailable)
                return fallback
            }
            let envelope = try await fetch(base, account, reference)
            // A block, account change or foreground receipt during fetch must take effect immediately.
            guard let latest = try load(), latest.account?.deviceId == account.deviceId,
                  let text = try latest.notificationText(for: envelope, now: milliseconds()) else {
                Diagnostics.event(.push, "preview.policy", .fallback, reason: .ineligible)
                return fallback
            }
            Diagnostics.event(.push, "preview.render", .ok)
            return content(sender: envelope.senderAccountId, text: text, showBody: latest.settings.showBody)
        } catch {
            Diagnostics.failure(.push, "preview.render", error, phase: .fallback)
            return fallback
        }
    }

    static func content(sender: UUID?, text: String?, showBody: Bool) -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = sender.map { "联系人 " + $0.wire.prefix(8) } ?? "SecretChat"
        content.body = sender != nil && showBody ? text ?? "你有一条新消息" : "你有一条新消息"
        return content
    }
}

final class NotificationCompletion: @unchecked Sendable {
    private let lock = NSLock()
    private var handler: ((UNNotificationContent) -> Void)?

    init(_ handler: @escaping (UNNotificationContent) -> Void) { self.handler = handler }

    func finish(_ content: UNNotificationContent) {
        let callback = lock.withLock {
            defer { handler = nil }
            return handler
        }
        callback?(content)
    }
}
