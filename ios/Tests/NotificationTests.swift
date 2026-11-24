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
