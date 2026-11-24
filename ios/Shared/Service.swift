// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

private final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

struct MailPage: Decodable {
    let envelopes: [Incoming]
    let through: Int64
    let nextAfter: Int64
    let done: Bool

    func validate(after: Int64, through expected: Int64?) throws {
        guard expected == nil || expected == through, through >= after,
              after <= nextAfter, nextAfter <= through else { throw ClientError.invalidEnvelope }
        var previous = after
        for item in envelopes {
            guard item.sequence > previous, item.sequence <= nextAfter else { throw ClientError.invalidEnvelope }
            previous = item.sequence
        }
        guard done ? nextAfter == through : (nextAfter > after && previous == nextAfter && nextAfter < through) else {
            throw ClientError.invalidEnvelope
        }
    }
}

struct Service {
    struct Failure: LocalizedError {
        let status: Int
        let requestID: UUID?
        var errorDescription: String? {
            let message = "服务请求失败（HTTP \(status)）。"
            return requestID.map { message + " 请求编号 " + $0.wire } ?? message
        }
    }

    typealias Exchange = (URLRequest) async throws -> (Data, URLResponse)
    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.timeoutIntervalForRequest = 15
        return URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }()

    let base: URL
    private let token: String?
    private let exchange: Exchange

    init(base: String, token: String? = nil, exchange: Exchange? = nil) throws {
        guard let url = URL(string: base), url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil else { throw ClientError.invalidServer }
        self.base = url
        self.token = token
        self.exchange = exchange ?? { request in
            try await Self.session.data(for: request)
        }
    }

    private func makeRequest(_ path: String, method: String = "GET", query: [URLQueryItem] = [],
                             body: Data? = nil) -> URLRequest {
        var url = base.appending(path: path)
        if !query.isEmpty { url.append(queryItems: query) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return request
    }

    private func data(_ path: String, method: String = "GET", query: [URLQueryItem] = [], body: Data? = nil,
                      status: Int = 200, operation: StaticString = #function) async throws -> Data {
        try await Diagnostics.span(.network, operation) {
            let (data, response) = try await exchange(makeRequest(path, method: method, query: query, body: body))
            guard let http = response as? HTTPURLResponse else { throw ClientError.invalidEnvelope }
            let requestID = http.value(forHTTPHeaderField: "X-Request-ID").flatMap(UUID.init(uuidString:))
            guard http.statusCode == status else { throw Failure(status: http.statusCode, requestID: requestID) }
            Diagnostics.event(.network, "http.response", .ok, code: http.statusCode, requestID: requestID)
            return data
        }
    }

    func challenge(email: String) async throws -> UUID {
        struct Challenge: Decodable { let challengeId: UUID }
        let body = try JSONEncoder().encode(["purpose":"register", "email":email])
        let bytes = try await data("api/challenges", method: "POST", body: body)
        return try JSONDecoder().decode(Challenge.self, from: bytes).challengeId
    }

    private struct ProtocolKeys: Codable {
        let registrationId: UInt32
        let kyber: PublicPrekey
    }

    func register(email: String, invite: String, challenge: UUID?, code: String, bundle: PublicBundle) async throws -> Account {
        let protocolKeys = ProtocolKeys(registrationId: bundle.registrationId, kyber: bundle.kyber)
        var fields = try ["inviteCode":invite, "identityKey":bundle.identity.base64EncodedString(),
            "signedPrekey":JSONEncoder().encode(bundle.signed).base64EncodedString(),
            "protocolPrekeys":JSONEncoder().encode(protocolKeys).base64EncodedString()]
        if let challenge {
            fields["challengeId"] = challenge.wire
            fields["code"] = code
        } else { fields["email"] = email }
        let bytes = try await data("api/accounts", method: "POST", body: JSONEncoder().encode(fields), status: 201)
        return try JSONDecoder().decode(Account.self, from: bytes)
    }

    func contacts() async throws -> [Contact] {
        try await JSONDecoder().decode([Contact].self, from: data("api/contacts"))
    }

    func invite() async throws -> String {
        struct Invite: Decodable { let code: String }
        return try await JSONDecoder().decode(Invite.self, from: data("api/invites", method: "POST")).code
    }

    func bundle(for peer: Contact) async throws -> PublicBundle {
        struct Response: Decodable {
            let accountId: UUID
            let deviceId: UUID
            let identityKey: Data
            let signedPrekey: Data
            let oneTimePrekey: OneTimePrekey?
            let protocolPrekeys: Data
        }
        let bytes = try await data("api/contacts/\(peer.id.wire)/prekeys", method: "POST")
        let reply = try JSONDecoder().decode(Response.self, from: bytes)
        guard reply.accountId == peer.id, reply.deviceId == peer.deviceId, reply.identityKey == peer.identityKey else {
            throw ClientError.changedContact
        }
        let extra = try JSONDecoder().decode(ProtocolKeys.self, from: reply.protocolPrekeys)
        return try PublicBundle(registrationId: extra.registrationId, identity: reply.identityKey,
            signed: JSONDecoder().decode(PublicPrekey.self, from: reply.signedPrekey), kyber: extra.kyber, oneTime: reply.oneTimePrekey)
    }

    private struct PrekeyCount: Decodable { let remaining: UInt32 }

    func remainingPrekeys() async throws -> UInt32 {
        try await JSONDecoder().decode(PrekeyCount.self, from: data("api/keys/one-time")).remaining
    }

    func upload(prekeys: [OneTimePrekey]) async throws {
        let bytes = try await data("api/keys/one-time", method: "POST", body: JSONEncoder().encode(["keys":prekeys]))
        _ = try JSONDecoder().decode(PrekeyCount.self, from: bytes)
    }

    func submit(_ packet: Outgoing) async throws -> UUID {
        struct Receipt: Decodable { let reference: UUID; let expiresAtMs: Int64 }
        let bytes = try await data("api/mailbox/envelopes", method: "POST", body: JSONEncoder().encode(packet), status: 202)
        let receipt = try JSONDecoder().decode(Receipt.self, from: bytes)
        guard receipt.expiresAtMs == packet.expiresAtMs else { throw ClientError.invalidEnvelope }
        return receipt.reference
    }

    func page(after: Int64, through: Int64?) async throws -> MailPage {
        var query = [URLQueryItem(name: "after", value: String(after)), URLQueryItem(name: "limit", value: "50")]
        if let through { query.append(URLQueryItem(name: "through", value: String(through))) }
        let page = try await JSONDecoder().decode(MailPage.self, from: data("api/mailbox/envelopes", query: query))
        try page.validate(after: after, through: through)
        return page
    }

    func acknowledge(_ ref: UUID) async throws {
        _ = try await data("api/mailbox/envelopes/\(ref.wire)/ack", method: "POST", status: 204)
    }

    func envelope(_ ref: UUID) async throws -> Incoming {
        let envelope = try await JSONDecoder().decode(Incoming.self, from: data("api/mailbox/envelopes/\(ref.wire)"))
        guard envelope.reference == ref else { throw ClientError.invalidEnvelope }
        return envelope
    }

    func outcome(_ ref: UUID) async throws -> Delivery {
        struct Outcome: Decodable { let reference: UUID; let status: Delivery }
        let reply = try await JSONDecoder().decode(Outcome.self, from: data("api/mailbox/outcomes/\(ref.wire)"))
        guard reply.reference == ref, [.accepted, .received, .expired].contains(reply.status) else { throw ClientError.invalidEnvelope }
        return reply.status
    }

    func bind(token: Data) async throws {
        let hex = token.map { String(format: "%02x", $0) }.joined()
        _ = try await data("api/mailbox/push-token", method: "PUT", body: JSONEncoder().encode(["token":hex]), status: 204)
    }

    func listen(onWake: () async -> Void) async throws {
        let request = makeRequest("ws")
        guard let url = request.url, var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw ClientError.invalidServer
        }
        parts.scheme = "wss"
        var upgrade = request
        upgrade.url = parts.url
        let socket = Self.session.webSocketTask(with: upgrade)
        Diagnostics.event(.network, "socket.connect", .begin)
        socket.resume()
        defer { socket.cancel(with: .goingAway, reason: nil) }
        try await withTaskCancellationHandler {
            while !Task.isCancelled {
                let message = try await socket.receive()
                if case .string(let json) = message,
                   let hint = try? JSONDecoder().decode([String: String].self, from: Data(json.utf8)),
                   ["ready","available","outcome"].contains(hint["type"]) {
                    Diagnostics.event(.network, "socket.wake", .ok)
                    await onWake()
                }
            }
        } onCancel: { socket.cancel(with: .goingAway, reason: nil) }
    }
}
