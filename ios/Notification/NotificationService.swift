// SPDX-License-Identifier: AGPL-3.0-only
import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
    private let lock = NSLock()
    private var active: NotificationCompletion?
    private var fallback: UNNotificationContent {
        NotificationPreview.content(sender: nil, text: nil, showBody: false)
    }

    override func didReceive(_ request: UNNotificationRequest, withContentHandler handler: @escaping (UNNotificationContent) -> Void) {
        let delivery = NotificationCompletion(handler)
        lock.withLock { active = delivery }
        guard let raw = request.content.userInfo["ref"] as? String, let ref = UUID(uuidString: raw) else {
            return delivery.finish(fallback)
        }
        Task {
            do {
                let vault = try Vault.shared(createKey: false)
                guard let profile = try vault.load(), let base = profile.server, let account = profile.account else {
                    return delivery.finish(fallback)
                }
                let service = try Service(base: base.absoluteString, token: account.token)
                let envelope = try await service.envelope(ref)
                // Re-read after network I/O so recent blocks, settings and foreground processing win.
                guard let latest = try vault.load(), latest.account?.deviceId == account.deviceId,
                      let text = try latest.notificationText(for: envelope, now: milliseconds()) else {
                    return delivery.finish(fallback)
                }
                delivery.finish(NotificationPreview.content(sender: envelope.senderAccountId, text: text,
                                                             showBody: latest.settings.showBody))
            } catch { delivery.finish(fallback) }
        }
    }

    override func serviceExtensionTimeWillExpire() {
        lock.withLock { active }?.finish(fallback)
    }
}
