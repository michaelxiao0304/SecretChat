// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import UserNotifications

enum NotificationPreview {
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
