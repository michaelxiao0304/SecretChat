import XCTest
@testable import SecretChat

final class DiagnosticsTests: XCTestCase {
    func testHttpErrorCarriesPhaseTraceStatusAndElapsedTimeWithoutSensitiveInputs() async throws {
        let endpoint = "private-service.invalid"
        let token = "bearer-secret"
        let ref = UUID()
        var lines: [DiagnosticRecord] = []
        let api = try Service(base: "https://" + endpoint, token: token) { _ in
            try httpReply(401, Data("response-secret".utf8))
        }
        await Diagnostics.$observer.withValue({ lines.append($0) }) {
            do { _ = try await api.envelope(ref); XCTFail("Expected HTTP failure") } catch {}
        }
        let failure = try XCTUnwrap(lines.first { $0.phase == .failed })
        XCTAssertEqual(failure.area, .network)
        XCTAssertEqual(failure.reason, .http)
        XCTAssertEqual(failure.code, 401)
        XCTAssertNotNil(failure.elapsedMS)
        XCTAssertNotNil(UUID(uuidString: failure.trace))
        XCTAssertEqual(Set(lines.map(\.trace)), [failure.trace])
        let log = lines.map(\.line).joined()
        for secret in [ref.wire, token, endpoint, "response-secret", "/api/mailbox"] {
            XCTAssertFalse(log.contains(secret))
        }
    }

    func testLogsCannotIncludeErrorDescriptionsFromProviders() async {
        var lines: [DiagnosticRecord] = []
        let error = NSError(domain: "user-secret", code: 23, userInfo: [NSLocalizedDescriptionKey: "email-and-message"])
        await Diagnostics.$observer.withValue({ lines.append($0) }) {
            do { try await Diagnostics.span(.storage, "load") { throw error } } catch {}
        }
        XCTAssertEqual(lines.map(\.phase), [.begin, .failed])
        XCTAssertEqual(lines.last?.reason, .unknown)
        XCTAssertFalse(lines.map(\.line).joined().contains("secret"))
        XCTAssertFalse(lines.map(\.line).joined().contains("email-and-message"))
    }
}

extension DiagnosticsTests {
    func testChallengeAndPushTokenNeverEnterSuccessfulRequestLogs() async throws {
        let challengeID = UUID()
        let api = try Service(base: "https://server.invalid", token: "secret-device-token") { request in
            if request.url?.path.hasSuffix("challenges") == true {
                return try httpReply(200, try JSONSerialization.data(withJSONObject: ["challengeId":challengeID.wire]))
            }
            return try httpReply(204, Data())
        }
        var records: [DiagnosticRecord] = []
        try await Diagnostics.$observer.withValue({ records.append($0) }) {
            _ = try await api.challenge(email: "secret-user@example.test")
            try await api.bind(token: Data([0xca, 0xfe, 0xba, 0xbe]))
        }
        let log = records.map(\.line).joined()
        for secret in ["secret-device-token", "secret-user", "cafebabe", challengeID.wire, "server.invalid"] {
            XCTAssertFalse(log.contains(secret))
        }
        XCTAssertEqual(Set(records.map(\.trace)).count, 2)
        XCTAssertEqual(records.compactMap(\.code), [200, 204])
    }
}

extension DiagnosticsTests {
    func testDiscardLogsDistinguishUnknownDeviceBlockAndDeadlineWithoutPeerIDs() throws {
        let peer = UUID(), device = UUID()
        var state = try Profile()
        func letter(device: UUID) -> Incoming {
            Incoming(sequence: 1, reference: UUID(), messageId: UUID(), senderAccountId: peer,
                     senderDeviceId: device, expiresAtMs: 100, ciphertext: Data([0]))
        }
        var records: [DiagnosticRecord] = []
        Diagnostics.$observer.withValue({ records.append($0) }) {
            state.consume(letter(device: device), now: 0)
            state.contacts = [Contact(accountId: peer, deviceId: device, identityKey: Data(), blocked: true)]
            state.consume(letter(device: device), now: 0)
            state.contacts[0].blocked = false
            state.consume(letter(device: UUID()), now: 0)
            state.consume(letter(device: device), now: 100)
        }
        XCTAssertEqual(records.compactMap { $0.reason?.rawValue }, ["unknownSender","blocked","deviceChanged","expired"])
        XCTAssertFalse(records.map(\.line).joined().contains(peer.wire))
        XCTAssertFalse(records.map(\.line).joined().contains(device.wire))
    }
}

extension DiagnosticsTests {
    func testServerRequestIDLinksErrorToServerAndRejectsMalformedHeaders() async throws {
        let requestID = UUID()
        for header in [requestID.wire, "private@example.test", ""] {
            var lines: [DiagnosticRecord] = []
            let api = try Service(base: "https://service.test") { _ in
                try httpReply(409, Data(), headers: ["X-Request-ID":header])
            }
            await Diagnostics.$observer.withValue({ lines.append($0) }) {
                do { _ = try await api.invite(); XCTFail("Expected conflict") }
                catch {
                    XCTAssertEqual(error.localizedDescription.contains(requestID.wire), header == requestID.wire)
                    XCTAssertFalse(error.localizedDescription.contains("private@example.test"))
                }
            }
            let failure = try XCTUnwrap(lines.first { $0.phase == .failed })
            XCTAssertEqual(failure.requestID, header == requestID.wire ? requestID : nil)
            XCTAssertFalse(failure.line.contains("private@example.test"))
        }
    }

    func testSuccessfulResponseLogsRequestIDWithLocalTrace() async throws {
        let requestID = UUID()
        var lines: [DiagnosticRecord] = []
        let api = try Service(base: "https://service.test") { _ in
            try httpReply(200, Data(#"{"code":"secret-invite"}"#.utf8), headers: ["X-Request-ID":requestID.wire])
        }
        _ = try await Diagnostics.$observer.withValue({ lines.append($0) }) { try await api.invite() }
        let response = try XCTUnwrap(lines.first { $0.requestID == requestID })
        XCTAssertEqual(response.code, 200)
        XCTAssertTrue(response.line.contains("requestId=" + requestID.wire))
        XCTAssertFalse(lines.map(\.line).joined().contains("secret-invite"))
        XCTAssertEqual(Set(lines.map(\.trace)).count, 1)
    }
}
