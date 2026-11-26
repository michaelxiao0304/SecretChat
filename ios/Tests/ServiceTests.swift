import XCTest
@testable import SecretChat

final class ServiceTests: XCTestCase {
    func testRejectsCleartextEmbeddedCredentialsAndAmbiguousBases() {
        for base in ["http://example.test", "https:///a", "https://user:pass@example.test", "https://example.test?q=t",
                     "https://example.test/#token", "file:///tmp/device"] {
            XCTAssertThrowsError(try Service(base: base))
        }
    }

    func testFetchesNotificationReferenceWithHeaderAndPreservesDeploymentPrefix() async throws {
        let ref = UUID(), sender = UUID(), device = UUID(), message = UUID()
        let service = try Service(base: "https://example.test/secretchat/", token: "device-token") { request in
            XCTAssertEqual(request.url?.path, "/secretchat/api/mailbox/envelopes/" + ref.wire)
            XCTAssertNil(request.url?.query)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
            XCTAssertEqual(request.httpMethod, "GET")
            return try httpReply(200, try JSONSerialization.data(withJSONObject: ["sequence": 9, "reference": ref.wire,
                "senderAccountId": sender.wire, "senderDeviceId": device.wire, "messageId": message.wire,
                "expiresAtMs": 2000, "ciphertext": "AQ=="]))
        }
        let envelope = try await service.envelope(ref)
        XCTAssertEqual(envelope.messageId, message)
        XCTAssertEqual(envelope.ciphertext, Data([1]))
    }

    func testRegistrationPublishesOnlyPublicKeysAndPreservesDeviceIdentity() async throws {
        let profile = try Profile(), accountId = UUID(), deviceId = UUID()
        let bundle = try profile.crypto.publicBundle()
        let service = try Service(base: "https://example.test") { request in
            XCTAssertEqual(request.url?.path, "/api/accounts")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: String])
            XCTAssertEqual(Set(body.keys), ["inviteCode","identityKey","signedPrekey","protocolPrekeys","email"])
            XCTAssertEqual(body["identityKey"], bundle.identity.base64EncodedString())
            let signed = try JSONDecoder().decode(PublicPrekey.self, from: XCTUnwrap(Data(base64Encoded: body["signedPrekey"] ?? "")))
            XCTAssertEqual(signed.key, bundle.signed.key)
            return try httpReply(201, try JSONSerialization.data(withJSONObject: ["accountId":accountId.wire, "deviceId":deviceId.wire,"token":"test"]))
        }
        let account = try await service.register(email: "alice", invite: "once", challenge: nil, code: "", bundle: bundle)
        XCTAssertEqual(account.deviceId, deviceId)
    }

    func testBadStatusAndMalformedSuccessNeverProduceReceipt() async throws {
        for status in [401, 409, 429, 503, 202] {
            let service = try Service(base: "https://test.invalid") { _ in try httpReply(status, Data()) }
            let packet = Outgoing(recipientAccountId: UUID(), recipientDeviceId: UUID(), messageId: UUID(),
                                  expiresAtMs: 1234, ciphertext: Data([1]))
            do {
                _ = try await service.submit(packet)
                XCTFail("Unexpected acceptance, HTTP \(status)")
            } catch let error as Service.Failure {
                XCTAssertEqual(error.status, status)
                XCTAssertNotEqual(status, 202)
            } catch { XCTAssertEqual(status, 202) }
        }
    }

    func testSnapshotBoundaryAndProgressAreValidated() throws {
        let valid = MailPage(envelopes: [], through: 5, nextAfter: 5, done: true)
        XCTAssertNoThrow(try valid.validate(after: 2, through: 5))
        for page in [MailPage(envelopes: [], through: 6, nextAfter: 6, done: true),
                     MailPage(envelopes: [], through: 5, nextAfter: 2, done: false),
                     MailPage(envelopes: [], through: 5, nextAfter: 4, done: true)] {
            XCTAssertThrowsError(try page.validate(after: 2, through: 5))
        }
    }
}

extension ServiceTests {
    func testChallengeInviteAndNoContentResponsesMatchServer() async throws {
        let id = UUID()
        let api = try Service(base: "https://service.test") { request in
            switch request.url?.path {
            case "/api/challenges":
                let body = try JSONDecoder().decode([String:String].self, from: XCTUnwrap(request.httpBody))
                XCTAssertEqual(body, ["purpose":"register","email":"me@test.invalid"])
                return try httpReply(200, try JSONSerialization.data(withJSONObject: ["challengeId":id.wire]))
            case "/api/invites": return try httpReply(200, Data(#"{"code":"single-use"}"#.utf8))
            default: XCTFail(); return try httpReply(500, Data())
            }
        }
        let challenge = try await api.challenge(email: "me@test.invalid")
        let invite = try await api.invite()
        XCTAssertEqual(challenge, id)
        XCTAssertEqual(invite, "single-use")
    }

    func testMismatchedReceiptEnvelopeAndOutcomeAreRejected() async throws {
        let id = UUID(), other = UUID(), device = UUID()
        let input = Outgoing(recipientAccountId: other, recipientDeviceId: device, messageId: id, expiresAtMs: 22, ciphertext: Data([1]))
        let api = try Service(base: "https://service.test") { request in
            let body: [String:Any] = ["reference":other.wire,"expiresAtMs":44,"status":"received","sequence":2,
                "messageId":id.wire,"senderAccountId":other.wire,"senderDeviceId":device.wire,"ciphertext":"AQ=="]
            return try httpReply(request.httpMethod == "POST" ? 202 : 200, try JSONSerialization.data(withJSONObject: body))
        }
        do { _ = try await api.submit(input); XCTFail() } catch ClientError.invalidEnvelope {}
        do { _ = try await api.envelope(id); XCTFail() } catch ClientError.invalidEnvelope {}
        do { _ = try await api.outcome(id); XCTFail() } catch ClientError.invalidEnvelope {}
    }
}
