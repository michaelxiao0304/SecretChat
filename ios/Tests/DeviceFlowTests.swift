import CryptoKit
import XCTest
@testable import SecretChat

@MainActor
final class DeviceFlowTests: XCTestCase {
    private let host = "https://service.test/secretchat"
    private let root = FileManager.default.temporaryDirectory.appending(path: UUID().wire)
    override func tearDown() { try? FileManager.default.removeItem(at: root) }

    func testRegisterSendLoseReceiptAndResumeWithSameCiphertext() async throws {
        let vault = try Vault(directory: root, key: SymmetricKey(size: .bits256))
        let peerState = try ClientState(), peerBundle = try peerState.publicBundle()
        let local = UUID(), peer = UUID(), device = UUID(), myDevice = UUID(), ref = UUID()
        let contact = Contact(accountId: peer, deviceId: device, identityKey: peerBundle.identity, blocked: false)
        var submissions: [Outgoing] = []
        var fetches = 0
        var uploaded = 0
        var sentToken: String?
        var outcome = "accepted"
        func api(_ base: String, _ token: String?) throws -> Service {
            try Service(base: base, token: token) { request in
                switch (request.httpMethod, request.url?.path) {
                case ("POST", "/secretchat/api/accounts"):
                    let body = try JSONDecoder().decode([String: String].self, from: XCTUnwrap(request.httpBody))
                    XCTAssertEqual(body["code"], "123456")
                    XCTAssertNotNil(body["challengeId"])
                    XCTAssertNil(body["email"])
                    return try httpReply(201, try JSONSerialization.data(withJSONObject: ["accountId":local.wire,"deviceId":myDevice.wire,"token":"session"]))
                case ("GET", "/secretchat/api/keys/one-time"):
                    return try httpReply(200, try JSONSerialization.data(withJSONObject: ["remaining":uploaded]))
                case ("POST", "/secretchat/api/keys/one-time"):
                    let batch = try JSONDecoder().decode([String:[OneTimePrekey]].self, from: XCTUnwrap(request.httpBody))
                    XCTAssertEqual(try vault.load()?.crypto.pendingPrekeys, batch["keys"])
                    uploaded = batch["keys"]?.count ?? 0
                    return try httpReply(200, try JSONSerialization.data(withJSONObject: ["remaining":uploaded]))
                case ("GET", "/secretchat/api/contacts"):
                    XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer session")
                    return try httpReply(200, try JSONEncoder().encode([contact]))
                case ("GET", "/secretchat/api/mailbox/envelopes"):
                    return try httpReply(200, Data(#"{"envelopes":[],"through":0,"nextAfter":0,"done":true}"#.utf8))
                case ("POST", "/secretchat/api/contacts/" + peer.wire + "/prekeys"):
                    fetches += 1
                    let keys = try JSONSerialization.data(withJSONObject: ["registrationId":peerBundle.registrationId,
                        "kyber":JSONSerialization.jsonObject(with: JSONEncoder().encode(peerBundle.kyber))])
                    return try httpReply(200, try JSONSerialization.data(withJSONObject: ["identityKey":peerBundle.identity.base64EncodedString(),
                        "accountId":peer.wire,"deviceId":device.wire,"signedPrekey":JSONEncoder().encode(peerBundle.signed).base64EncodedString(),
                        "protocolPrekeys":keys.base64EncodedString()]))
                case ("POST", "/secretchat/api/mailbox/envelopes"):
                    let submission = try JSONDecoder().decode(Outgoing.self, from: XCTUnwrap(request.httpBody))
                    submissions.append(submission)
                    XCTAssertEqual(try vault.load()?.outbox.first, submission)
                    if submissions.count == 1 { throw URLError(.networkConnectionLost) }
                    return try httpReply(202, try JSONSerialization.data(withJSONObject: ["reference":ref.wire,"expiresAtMs":submission.expiresAtMs]))
                case ("GET", "/secretchat/api/mailbox/outcomes/" + ref.wire):
                    return try httpReply(200, try JSONSerialization.data(withJSONObject: ["reference":ref.wire,"status":outcome]))
                case ("PUT", "/secretchat/api/mailbox/push-token"):
                    sentToken = try JSONDecoder().decode([String:String].self, from: XCTUnwrap(request.httpBody))["token"]
                    return try httpReply(204, Data())
                default: XCTFail("Unexpected route \(request.url?.path ?? "")"); return try httpReply(500, Data())
                }
            }
        }
        let first = DeviceModel(openVault: { vault }, makeService: api)
        first.open()
        await first.perform { try await first.register(server: host, email: "demo", invite: "invite", challenge: UUID(), code: "123456") }
        XCTAssertEqual(first.profile?.contacts, [contact])
        await first.perform { try await first.send("只在两端解密", to: contact) }
        XCTAssertNotNil(first.issue)
        XCTAssertEqual(first.profile?.messages.first?.status, .pending)
        let restarted = DeviceModel(openVault: { vault }, makeService: api)
        restarted.open()
        await restarted.tokenChanged(Data([0, 10, 255]))
        XCTAssertNil(restarted.issue)
        XCTAssertEqual(sentToken, "000aff")
        XCTAssertEqual(submissions.count, 2)
        XCTAssertEqual(submissions.first, submissions.last)
        XCTAssertEqual(fetches, 1)
        XCTAssertEqual(uploaded, 16)
        XCTAssertEqual(restarted.profile?.messages.first?.status, .accepted)
        XCTAssertTrue(try XCTUnwrap(restarted.profile).outbox.isEmpty)
        var recipient = peerState
        let wire = try XCTUnwrap(submissions.first)
        XCTAssertEqual(try recipient.receive(Packet(id: wire.messageId, ciphertext: wire.ciphertext), from: local, to: peer), "只在两端解密")
        outcome = "received"
        await restarted.refresh()
        XCTAssertEqual(restarted.profile?.messages.first?.status, .received)
        await restarted.setPreview(true)
        XCTAssertEqual(try vault.load()?.settings.showBody, true)
    }

    func testInvitationAndEmailChallengeUseAuthenticatedAndAnonymousRoutesWithSafeLogs() async throws {
        let vault = try Vault(directory: root, key: SymmetricKey(size: .bits256))
        let challenge = UUID(), secret = "caseSensitive-testToken", email = "private@example.test"
        var requests: [URLRequest] = []
        let model = DeviceModel(openVault: { vault }, makeService: { base, token in
            try Service(base: base, token: token) { request in
                requests.append(request)
                if request.url?.path == "/secretchat/api/challenges" {
                    return try httpReply(200, JSONEncoder().encode(["challengeId": challenge.wire]))
                }
                return try httpReply(200, JSONEncoder().encode(["code": secret]))
            }
        })
        model.open()
        var events: [DiagnosticRecord] = []
        try await Diagnostics.$observer.withValue({ events.append($0) }) {
            let returned = try await model.requestCode(server: host, email: email)
            XCTAssertEqual(returned, challenge)
            var profile = try XCTUnwrap(model.profile)
            profile.account = Account(accountId: UUID(), deviceId: UUID(), token: "device-test")
            profile.server = URL(string: host)
            try model.save(profile)
            let issued = try await model.issueInvite()
            XCTAssertEqual(issued, secret)
        }
        XCTAssertEqual(requests.map { $0.url?.path }, ["/secretchat/api/challenges", "/secretchat/api/invites"])
        XCTAssertNil(requests[0].value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(requests[1].value(forHTTPHeaderField: "Authorization"), "Bearer device-test")
        for stage in ["registration.challenge", "invite.issue"] {
            XCTAssertTrue(events.contains { $0.stage.description == stage && $0.phase == .ok })
        }
        let rendered = events.map(\.line).joined()
        for privateValue in [email, host, secret, challenge.wire, "device-test"] {
            XCTAssertFalse(rendered.contains(privateValue))
        }
    }

    func testRemovedContactStopsFirstSendBeforeFetchingPrekeys() async throws {
        let vault = try Vault(directory: root, key: SymmetricKey(size: .bits256))
        var requests = 0
        let model = DeviceModel(openVault: { vault }, makeService: { base, token in
            try Service(base: base, token: token) { _ in
                requests += 1
                throw URLError(.notConnectedToInternet)
            }
        })
        model.open()
        var profile = try XCTUnwrap(model.profile)
        profile.account = Account(accountId: UUID(), deviceId: UUID(), token: "local-test")
        profile.server = URL(string: "https://example.test")
        try model.save(profile)
        let removed = Contact(accountId: UUID(), deviceId: UUID(), identityKey: Data([7]), blocked: false)
        await model.perform { try await model.send("草稿", to: removed) }
        XCTAssertEqual(requests, 0)
        XCTAssertEqual(model.issue, ContactAccess.missing.explanation)
        XCTAssertTrue(try XCTUnwrap(model.profile).outbox.isEmpty)
    }

    func testUnavailableVaultCanBeRetriedWithoutReplacingIdentity() throws {
        let vault = try Vault(directory: root, key: SymmetricKey(size: .bits256))
        var available = false
        let model = DeviceModel(openVault: {
            if !available { throw ClientError.unavailableStorage }
            return vault
        })
        model.open()
        XCTAssertNotNil(model.issue)
        XCTAssertNil(model.profile)
        available = true
        model.open()
        let key = try XCTUnwrap(model.profile).crypto.publicBundle().identity
        model.open()
        XCTAssertEqual(try vault.load()?.crypto.publicBundle().identity, key)
    }
}
