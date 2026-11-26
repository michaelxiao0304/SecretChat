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
                case "/api/contacts": return try httpReply(200, Data("[]".utf8))
                case "/api/mailbox/envelopes":
                    if url.query?.contains("through=") == true { return try httpReply(503, Data()) }
                    let letter: [String: Any] = ["sequence":1,"reference":ref.wire,"messageId":UUID().wire,
                        "senderAccountId":UUID().wire,"senderDeviceId":UUID().wire,"ciphertext":"AQ==","expiresAtMs":1000]
                    return try httpReply(200, try JSONSerialization.data(withJSONObject: ["envelopes":[letter],
                        "through":2,"nextAfter":failOnDisk ? 2 : 1,"done":failOnDisk]))
                default: XCTFail("Unexpected request"); return try httpReply(500, Data())
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
            case "/api/contacts": return try httpReply(200, Data("[]".utf8))
            case "/api/mailbox/envelopes": return try httpReply(200, Data(#"{"envelopes":[],"through":7,"nextAfter":7,"done":true}"#.utf8))
            case "/api/mailbox/envelopes/" + ref.wire + "/ack": return try httpReply(ackResults.removeFirst(), Data())
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

extension SyncTests {
    func testSnapshotRequestsFixedThroughAndPublishesOnlyAfterLastPage() async throws {
        var state = try Profile()
        state.account = account
        var afters: [String] = [], commits: [Int64] = []
        let ref = UUID()
        let service = try Service(base: "https://test.invalid") { request in
            let url = try XCTUnwrap(request.url)
            if url.path == "/api/contacts" { return try httpReply(200, Data("[]".utf8)) }
            if url.path.hasSuffix("/ack") {
                XCTAssertEqual(commits.first, 6)
                return try httpReply(410, Data())
            }
            let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let query = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
            afters.append(query["after"] ?? "")
            if query["after"] == "0" {
                XCTAssertNil(query["through"])
                let envelope: [String:Any] = ["reference":ref.wire,"messageId":UUID().wire,"senderAccountId":UUID().wire,
                    "senderDeviceId":UUID().wire,"expiresAtMs":1234,"sequence":3,"ciphertext":"AQ=="]
                return try httpReply(200, try JSONSerialization.data(withJSONObject: ["through":6,"nextAfter":3,"done":false,"envelopes":[envelope]]))
            }
            XCTAssertEqual(query["through"], "6")
            return try httpReply(200, Data(#"{"through":6,"nextAfter":6,"done":true,"envelopes":[]}"#.utf8))
        }
        try await MailboxSync(service: service).run(from: state) { next in
            commits.append(next.cursor)
            state = next
        }
        XCTAssertEqual(afters, ["0","3"])
        XCTAssertEqual(commits, [6,6])
        XCTAssertTrue(state.acks.isEmpty)
    }
}

extension SyncTests {
    func testConflictPreservesCiphertextAndExpiredSubmissionHasNoFalseReceipt() async throws {
        var initial = try Profile()
        initial.account = account
        let remote = try ClientState().publicBundle()
        let peer = Contact(accountId: UUID(), deviceId: UUID(), identityKey: remote.identity, blocked: false)
        initial.contacts = [peer]
        let queued = try initial.queue("waiting", for: peer, bundle: remote, now: 0)
        for status in [409, 400] {
            var saved = initial
            let service = try Service(base: "https://service.test") { request in
                if request.url?.path == "/api/contacts" { return try httpReply(200, try JSONEncoder().encode([peer])) }
                if request.httpMethod == "POST" { return try httpReply(status, Data()) }
                return try httpReply(200, Data(#"{"through":0,"nextAfter":0,"done":true,"envelopes":[]}"#.utf8))
            }
            try await MailboxSync(service: service).run(from: saved) { saved = $0 }
            XCTAssertEqual(saved.outbox, status == 409 ? [queued] : [])
            XCTAssertEqual(saved.messages.first?.status, status == 409 ? .pending : .unconfirmed)
            XCTAssertNil(saved.messages.first?.reference)
        }
    }

    func testExpiredOutcomeAndPurgedReceiptHaveDistinctStates() async throws {
        var state = try Profile()
        state.account = account
        let expired = UUID(), gone = UUID()
        state.messages = [expired, gone].map {
            ChatMessage(id: UUID(), peer: UUID(), outgoing: true, text: "test", status: .accepted, reference: $0)
        }
        let api = try Service(base: "https://test.invalid") { request in
            switch request.url?.path {
            case "/api/contacts": return try httpReply(200, Data("[]".utf8))
            case "/api/mailbox/envelopes": return try httpReply(200, Data(#"{"through":0,"nextAfter":0,"done":true,"envelopes":[]}"#.utf8))
            case "/api/mailbox/outcomes/" + gone.wire: return try httpReply(404, Data())
            case "/api/mailbox/outcomes/" + expired.wire:
                return try httpReply(200, try JSONSerialization.data(withJSONObject: ["reference":expired.wire,"status":"expired"]))
            default: XCTFail(); return try httpReply(500, Data())
            }
        }
        try await MailboxSync(service: api).run(from: state) { state = $0 }
        XCTAssertEqual(state.messages.map(\.status), [.expired, .unconfirmed])
    }
}
