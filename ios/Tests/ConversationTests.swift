import XCTest
@testable import SecretChat

final class ConversationTests: XCTestCase {
    let alice = UUID()
    let bob = UUID()

    func testTwoIndependentDevicesCanExchangeAndResumeAfterRestart() throws {
        var sender = try ClientState()
        var receiver = try ClientState()
        let bundle = try receiver.publicBundle()
        let first = try sender.prepare("你好 👋", from: alice, to: bob, bundle: bundle)
        XCTAssertNil(first.ciphertext.range(of: Data("你好 👋".utf8)))
        XCTAssertEqual(try receiver.receive(first, from: alice, to: bob), "你好 👋")
        receiver = try JSONDecoder().decode(ClientState.self, from: JSONEncoder().encode(receiver))
        let reply = try receiver.prepare("收到了", from: bob, to: alice, bundle: nil)
        XCTAssertEqual(try sender.receive(reply, from: bob, to: alice), "收到了")
    }

    func testOutOfOrderAndRepeatedDelivery() throws {
        var sender = try ClientState()
        var receiver = try ClientState()
        let first = try sender.prepare("first", from: alice, to: bob, bundle: receiver.publicBundle())
        let second = try sender.prepare("second", from: alice, to: bob, bundle: nil)
        XCTAssertEqual(try receiver.receive(second, from: alice, to: bob), "second")
        XCTAssertEqual(try receiver.receive(first, from: alice, to: bob), "first")
        XCTAssertNil(try receiver.receive(second, from: alice, to: bob))
    }

    func testPreviewReadsSnapshotWithoutConsumingRatchet() throws {
        var sender = try ClientState()
        var receiver = try ClientState()
        let message = try sender.prepare("private", from: alice, to: bob, bundle: receiver.publicBundle())
        XCTAssertEqual(try receiver.preview(message, from: alice, to: bob), "private")
        XCTAssertEqual(try receiver.preview(message, from: alice, to: bob), "private")
        XCTAssertEqual(try receiver.receive(message, from: alice, to: bob), "private")
    }
}

extension ConversationTests {
    func testModifiedCiphertextAndMismatchedIDsDoNotConsumeValidMessage() throws {
        var sender = try ClientState()
        var receiver = try ClientState()
        let original = try sender.prepare("original", from: alice, to: bob, bundle: receiver.publicBundle())
        var corrupted = original.ciphertext
        corrupted[corrupted.count - 1] ^= 1
        for invalid in [Packet(id: UUID(), ciphertext: original.ciphertext),
                        Packet(id: original.id, ciphertext: corrupted),
                        Packet(id: original.id, ciphertext: Data()),
                        Packet(id: original.id, ciphertext: Data([0]))] {
            XCTAssertThrowsError(try receiver.receive(invalid, from: alice, to: bob))
        }
        XCTAssertEqual(try receiver.receive(original, from: alice, to: bob), "original")
    }

    func testRejectsMissingSessionAndInvalidText() throws {
        var sender = try ClientState()
        let bundle = try ClientState().publicBundle()
        XCTAssertThrowsError(try sender.prepare("hello", from: alice, to: bob, bundle: nil))
        for invalid in ["  \n", String(repeating: "字", count: 6000)] {
            XCTAssertThrowsError(try sender.prepare(invalid, from: alice, to: bob, bundle: bundle))
        }
        XCTAssertFalse(sender.hasSession(with: bob))
    }

    func testInvalidPrekeySignatureCannotEstablishSession() throws {
        var sender = try ClientState()
        var bundle = try ClientState().publicBundle()
        bundle.signed.signature[0] ^= 1
        XCTAssertThrowsError(try sender.prepare("hello", from: alice, to: bob, bundle: bundle))
        XCTAssertFalse(sender.hasSession(with: bob))
    }
}
