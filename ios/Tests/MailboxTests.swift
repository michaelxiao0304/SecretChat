import XCTest
@testable import SecretChat

final class MailboxTests: XCTestCase {
    var alice = Account(accountId: UUID(), deviceId: UUID(), token: "alice-test")
    var bob = Account(accountId: UUID(), deviceId: UUID(), token: "bob-test")

    func testSubmissionIsStableAcrossRestartAndOnlyCiphertextGoesOnWire() throws {
        var me = try Profile()
        me.account = alice
        let other = try Profile()
        let bundle = try other.crypto.publicBundle()
        let peer = Contact(accountId: bob.accountId, deviceId: bob.deviceId, identityKey: bundle.identity, blocked: false)
        me.contacts = [peer]
        let prepared = try me.queue("secret payload", for: peer, bundle: bundle, now: 1000)
        let reopened = try JSONDecoder().decode(Profile.self, from: JSONEncoder().encode(me))
        let pending = try XCTUnwrap(reopened.outbox.first)
        XCTAssertEqual(pending, prepared)
        let body = try JSONSerialization.jsonObject(with: JSONEncoder().encode(pending)) as? [String: Any]
        XCTAssertEqual(Set(body?.keys.map { $0 } ?? []), ["recipientAccountId","recipientDeviceId","messageId","expiresAtMs","ciphertext"])
        XCTAssertEqual(body?["messageId"] as? String, prepared.messageId.wire)
        XCTAssertEqual(body?["recipientAccountId"] as? String, bob.accountId.wire)
        XCTAssertEqual(prepared.expiresAtMs, 1000 + 6 * 24 * 60 * 60 * 1000)
        XCTAssertEqual(reopened.messages.first?.status, .pending)
        me.accept(prepared, reference: UUID())
        XCTAssertEqual(me.messages.first?.status, .accepted)
        XCTAssertTrue(me.outbox.isEmpty)
    }

    func testIdentityDeviceAndBlockingGateNewAndQueuedMessages() throws {
        var me = try Profile()
        me.account = alice
        let bundle = try ClientState().publicBundle()
        let peer = Contact(accountId: bob.accountId, deviceId: bob.deviceId, identityKey: bundle.identity, blocked: false)
        me.contacts = [peer]
        let pending = try me.queue("private", for: peer, bundle: bundle, now: 0)
        for changed in [
            Contact(accountId: bob.accountId, deviceId: UUID(), identityKey: bundle.identity, blocked: false),
            Contact(accountId: bob.accountId, deviceId: bob.deviceId, identityKey: Data([1]), blocked: false),
            Contact(accountId: bob.accountId, deviceId: bob.deviceId, identityKey: bundle.identity, blocked: true)
        ] {
            me.contacts = [changed]
            XCTAssertFalse(me.canRetry(pending))
            XCTAssertThrowsError(try me.queue("next", for: changed, bundle: nil, now: 1))
        }
        me.contacts = []
        XCTAssertFalse(me.canRetry(pending))
    }

    func testCommittedPageCanBeReplayedWithoutRedisplayAndAckSurvivesRestart() throws {
        var sender = try Profile(), receiver = try Profile()
        sender.account = alice
        receiver.account = bob
        let bundle = try receiver.crypto.publicBundle()
        let target = Contact(accountId: bob.accountId, deviceId: bob.deviceId, identityKey: bundle.identity, blocked: false)
        sender.contacts = [target]
        let outbound = try sender.queue("only once", for: target, bundle: bundle, now: 0)
        receiver.contacts = [try Contact(accountId: alice.accountId, deviceId: alice.deviceId,
                                          identityKey: sender.crypto.publicBundle().identity, blocked: false)]
        let envelope = Incoming(sequence: 3, reference: UUID(), messageId: outbound.messageId, senderAccountId: alice.accountId,
                                senderDeviceId: alice.deviceId, expiresAtMs: outbound.expiresAtMs, ciphertext: outbound.ciphertext)
        XCTAssertEqual(try receiver.notificationText(for: envelope, now: 1), "only once")
        XCTAssertTrue(receiver.messages.isEmpty)
        receiver.consume(envelope, now: 1)
        receiver = try JSONDecoder().decode(Profile.self, from: JSONEncoder().encode(receiver))
        receiver.consume(envelope, now: 2)
        XCTAssertEqual(receiver.messages.map(\.text), ["only once"])
        XCTAssertEqual(receiver.acks, [envelope.reference])
        XCTAssertNil(try receiver.notificationText(for: envelope, now: 2))
    }

    func testExpiredBlockedAndUnknownSendersLeaveOnlyAck() throws {
        var profile = try Profile()
        profile.account = bob
        let letter = Incoming(sequence: 1, reference: UUID(), messageId: UUID(), senderAccountId: alice.accountId,
                              senderDeviceId: alice.deviceId, expiresAtMs: 20, ciphertext: Data([0]))
        profile.consume(letter, now: 0)
        profile.contacts = [Contact(accountId: alice.accountId, deviceId: alice.deviceId, identityKey: Data(), blocked: true)]
        profile.consume(letter, now: 1)
        profile.contacts[0].blocked = false
        profile.consume(letter, now: 20)
        XCTAssertNil(try profile.notificationText(for: letter, now: 20))
        XCTAssertEqual(profile.acks, [letter.reference])
        XCTAssertTrue(profile.messages.isEmpty)
    }

    func testUndecryptableEnvelopeIsRecordedOnceWithoutBody() throws {
        var profile = try Profile()
        profile.account = bob
        profile.contacts = [Contact(accountId: alice.accountId, deviceId: alice.deviceId, identityKey: Data(), blocked: false)]
        let letter = Incoming(sequence: 1, reference: UUID(), messageId: UUID(), senderAccountId: alice.accountId,
                              senderDeviceId: alice.deviceId, expiresAtMs: 20, ciphertext: Data([0]))
        profile.consume(letter, now: 1)
        profile.consume(letter, now: 2)
        XCTAssertEqual(profile.messages.map(\.status), [.unreadable])
        XCTAssertNil(profile.messages.first?.text)
    }
}
