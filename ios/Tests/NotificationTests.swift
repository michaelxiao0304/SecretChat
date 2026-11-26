import XCTest
import UserNotifications
@testable import SecretChat

final class NotificationTests: XCTestCase {
    func testGenericFallbackAndPreviewPrivacy() {
        let empty = NotificationPreview.content(sender: nil, text: "private", showBody: true)
        XCTAssertEqual(empty.title, "SecretChat")
        XCTAssertEqual(empty.body, "你有一条新消息")
        XCTAssertTrue(empty.userInfo.isEmpty)
        let peer = UUID()
        let hidden = NotificationPreview.content(sender: peer, text: "private", showBody: false)
        XCTAssertTrue(hidden.title.contains(String(peer.wire.prefix(8))))
        XCTAssertEqual(hidden.body, empty.body)
        XCTAssertEqual(NotificationPreview.content(sender: peer, text: "private", showBody: true).body, "private")
    }

    func testTimeoutAndCompletionRaceDeliversExactlyOnce() {
        let lock = NSLock()
        var delivered = 0
        let gate = NotificationCompletion { _ in
            lock.withLock { delivered += 1 }
        }
        DispatchQueue.concurrentPerform(iterations: 30) { _ in
            gate.finish(NotificationPreview.content(sender: nil, text: nil, showBody: false))
        }
        XCTAssertEqual(delivered, 1)
    }
}

extension NotificationTests {
    func testPreviewReflectsChangesDuringFetchAndFallsBackOnNetworkFailure() async throws {
        var alice = try ClientState(), bob = try Profile()
        let sender = UUID(), senderDevice = UUID(), recipient = UUID(), ref = UUID()
        bob.account = Account(accountId: recipient, deviceId: UUID(), token: "local")
        bob.server = URL(string: "https://service.test")
        bob.contacts = [try Contact(accountId: sender, deviceId: senderDevice,
                                    identityKey: alice.publicBundle().identity, blocked: false)]
        let packet = try alice.prepare("private", from: sender, to: recipient, bundle: bob.crypto.publicBundle())
        let incoming = Incoming(sequence: 1, reference: ref, messageId: packet.id, senderAccountId: sender,
            senderDeviceId: senderDevice, expiresAtMs: milliseconds() + 30000, ciphertext: packet.ciphertext)
        var loads = 0
        func render(_ response: () throws -> Incoming) async -> UNNotificationContent {
            await NotificationPreview.render(reference: ref, load: { loads += 1; return bob },
                fetch: { _, account, reference in
                    XCTAssertEqual(reference, ref)
                    XCTAssertEqual(account.accountId, recipient)
                    return try response()
                })
        }
        let hidden = await render { incoming }
        XCTAssertTrue(hidden.title.contains(String(sender.wire.prefix(8))))
        XCTAssertEqual(hidden.body, "你有一条新消息")
        bob.settings.showBody = true
        let shown = await render { incoming }
        XCTAssertEqual(shown.body, "private")
        let blocked = await render { bob.contacts[0].blocked = true; return incoming }
        XCTAssertEqual(blocked.title, "SecretChat")
        XCTAssertEqual(blocked.body, hidden.body)
        let offline = await render { throw URLError(.notConnectedToInternet) }
        XCTAssertEqual(offline.title, "SecretChat")
        XCTAssertEqual(loads, 7)
        XCTAssertTrue(bob.acks.isEmpty)
        XCTAssertTrue(bob.messages.isEmpty)
    }
}
