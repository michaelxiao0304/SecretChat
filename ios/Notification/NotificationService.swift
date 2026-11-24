// SPDX-License-Identifier: AGPL-3.0-only
import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
    private let lock = NSLock()
    private var active: (UUID, NotificationCompletion)?

    override func didReceive(_ request: UNNotificationRequest, withContentHandler handler: @escaping (UNNotificationContent) -> Void) {
        let delivery = NotificationCompletion(handler)
        let trace = UUID()
        lock.withLock { active = (trace, delivery) }
        Task {
            await Diagnostics.$traceID.withValue(trace) {
                await Diagnostics.span(.push, "extension.receive") {
                    guard let raw = request.content.userInfo["ref"] as? String, let reference = UUID(uuidString: raw) else {
                        Diagnostics.event(.push, "payload.reference", .fallback, reason: .invalidData)
                        return delivery.finish(NotificationPreview.content(sender: nil, text: nil, showBody: false))
                    }
                    let content = await NotificationPreview.render(reference: reference,
                        load: { try Vault.shared(createKey: false).load() },
                        fetch: { base, account, ref in
                            try await Service(base: base.absoluteString, token: account.token).envelope(ref)
                        })
                    delivery.finish(content)
                }
            }
        }
    }

    override func serviceExtensionTimeWillExpire() {
        guard let (trace, delivery) = lock.withLock({ active }) else { return }
        Diagnostics.$traceID.withValue(trace) {
            Diagnostics.event(.push, "extension.deadline", .fallback, reason: .timeout)
            delivery.finish(NotificationPreview.content(sender: nil, text: nil, showBody: false))
        }
    }
}
