import XCTest
@testable import SecretChat

@MainActor
final class SyncTests: XCTestCase {
    let account = Account(accountId: UUID(), deviceId: UUID(), token: "test")

    func testIncompleteSnapshotOrFailedLocalCommitNeverAcknowledges() async throws {
        let ref = UUID()
        for failOnDisk in [false, true] {
            var seen: [String] = []
            var state = try Profile()
            state.account = account
            let service = try Service(base: "https://test.invalid") { request in
                let url = try XCTUnwrap(request.url)
                seen.append(url.path)
                switch url.path {
                case "/api/contacts": return (200, Data("[]".utf8))
                case "/api/mailbox/envelopes":
                    if url.query?.contains("through=") == true { return (503, Data()) }
                    let letter: [String: Any] = ["sequence":1,"reference":ref.wire,"messageId":UUID().wire,
                        "senderAccountId":UUID().wire,"senderDeviceId":UUID().wire,"ciphertext":"AQ==","expiresAtMs":1000]
                    return (200, try JSONSerialization.data(withJSONObject: ["envelopes":[letter],
                        "through":2,"nextAfter":failOnDisk ? 2 : 1,"done":failOnDisk]))
                default: XCTFail("Unexpected request"); return (500, Data())
                }
            }
            let sync = MailboxSync(service: service)
            do {
                try await sync.run(from: state) { value in
                    if failOnDisk && value.cursor == 2 { throw CocoaError(.fileWriteOutOfSpace) }
                    state = value
                }
                XCTFail("Expected failure")
            } catch {}
            XCTAssertEqual(state.cursor, 0)
            XCTAssertTrue(state.acks.isEmpty)
            XCTAssertFalse(seen.contains { $0.hasSuffix("/ack") })
        }
    }

    func testLostAckIsDurableAndRetriedAfterRestart() async throws {
        var state = try Profile()
        state.account = account
        let ref = UUID()
        state.acks = [ref]
        var ackResults = [503, 204]
        let service = try Service(base: "https://test.invalid") { request in
            switch request.url?.path {
            case "/api/contacts": return (200, Data("[]".utf8))
            case "/api/mailbox/envelopes": return (200, Data(#"{"envelopes":[],"through":7,"nextAfter":7,"done":true}"#.utf8))
            case "/api/mailbox/envelopes/" + ref.wire + "/ack": return (ackResults.removeFirst(), Data())
            default: throw ClientError.invalidEnvelope
            }
        }
        let sync = MailboxSync(service: service)
        do { try await sync.run(from: state) { state = $0 }; XCTFail("Expected lost ack") } catch {}
        XCTAssertEqual(state.acks, [ref])
        XCTAssertEqual(state.cursor, 7)
        let disk = try JSONEncoder().encode(state)
        try await sync.run(from: JSONDecoder().decode(Profile.self, from: disk)) { state = $0 }
        XCTAssertTrue(state.acks.isEmpty)
        XCTAssertTrue(ackResults.isEmpty)
    }
}
