// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

struct Account: Codable {
    let accountId: UUID
    let deviceId: UUID
    let token: String
}

struct Contact: Codable, Identifiable, Equatable {
    let accountId: UUID
    let deviceId: UUID
    let identityKey: Data
    var blocked: Bool
    var id: UUID { accountId }
}

struct Outgoing: Codable, Equatable {
    let recipientAccountId: UUID
    let recipientDeviceId: UUID
    let messageId: UUID
    let expiresAtMs: Int64
    let ciphertext: Data

    func encode(to encoder: Encoder) throws {
        var body = encoder.container(keyedBy: CodingKeys.self)
        try body.encode(recipientAccountId.wire, forKey: .recipientAccountId)
        try body.encode(recipientDeviceId.wire, forKey: .recipientDeviceId)
        try body.encode(messageId.wire, forKey: .messageId)
        try body.encode(expiresAtMs, forKey: .expiresAtMs)
        try body.encode(ciphertext, forKey: .ciphertext)
    }
}

struct Incoming: Decodable {
    let sequence: Int64
    let reference: UUID
    let messageId: UUID
    let senderAccountId: UUID
    let senderDeviceId: UUID
    let expiresAtMs: Int64
    let ciphertext: Data
}

enum Delivery: String, Codable {
    case pending, accepted, received, unreadable, expired, unconfirmed
}
struct ChatMessage: Codable, Identifiable {
    let id: UUID
    let peer: UUID
    let outgoing: Bool
    let text: String?
    var status: Delivery
    var reference: UUID?
}

struct Settings: Codable {
    var showBody = false
}
struct Profile: Codable {
    var crypto: ClientState
    var account: Account?
    var server: URL?
    var contacts: [Contact] = []
    // The first session pins both the service device and the locally authenticated identity.
    private var devices: [UUID: UUID] = [:]
    var settings = Settings()
    var messages: [ChatMessage] = []
    var outbox: [Outgoing] = []
    var acks: Set<UUID> = []
    var cursor: Int64 = 0

    init() throws { crypto = try ClientState() }

    func allows(_ peer: Contact) -> Bool {
        !peer.blocked && matchesBinding(peer)
    }

    private func matchesBinding(_ peer: Contact) -> Bool {
        let key = crypto.peerIdentity(peer.id)
        return (key == nil || key == peer.identityKey) && (devices[peer.id] == nil || devices[peer.id] == peer.deviceId)
    }

    func canRetry(_ outgoing: Outgoing) -> Bool {
        guard let peer = contacts.first(where: { $0.id == outgoing.recipientAccountId }) else { return false }
        return allows(peer) && peer.deviceId == outgoing.recipientDeviceId
    }

    mutating func queue(_ text: String, for peer: Contact, bundle: PublicBundle?, now: Int64) throws -> Outgoing {
        guard let account else { throw ClientError.missingAccount }
        guard contacts.contains(peer), allows(peer), bundle == nil || bundle?.identity == peer.identityKey else {
            throw ClientError.changedContact
        }
        let packet = try crypto.prepare(text, from: account.accountId, to: peer.id, bundle: bundle)
        devices[peer.id] = peer.deviceId
        let outgoing = Outgoing(recipientAccountId: peer.id, recipientDeviceId: peer.deviceId, messageId: packet.id,
                                expiresAtMs: now + 6 * 86_400_000, ciphertext: packet.ciphertext)
        outbox.append(outgoing)
        messages.append(ChatMessage(id: packet.id, peer: peer.id, outgoing: true, text: text, status: .pending))
        return outgoing
    }

    mutating func accept(_ outgoing: Outgoing, reference: UUID) {
        guard let index = messages.firstIndex(where: { $0.outgoing && $0.id == outgoing.messageId }) else { return }
        messages[index].status = .accepted
        messages[index].reference = reference
        outbox.removeAll { $0.messageId == outgoing.messageId }
    }

    private func sender(of envelope: Incoming, now: Int64) -> Contact? {
        guard envelope.expiresAtMs > now,
              let contact = contacts.first(where: { $0.id == envelope.senderAccountId }),
              contact.deviceId == envelope.senderDeviceId, allows(contact) else { return nil }
        return contact
    }

    private func opened(_ envelope: Incoming, from peer: Contact) throws -> (ClientState, String)? {
        guard let account else { throw ClientError.missingAccount }
        var copy = crypto
        let packet = Packet(id: envelope.messageId, ciphertext: envelope.ciphertext)
        guard let text = try copy.receive(packet, from: peer.id, to: account.accountId) else { return nil }
        guard copy.peerIdentity(peer.id) == peer.identityKey else { throw ClientError.changedContact }
        return (copy, text)
    }

    func notificationText(for envelope: Incoming, now: Int64) throws -> String? {
        guard !acks.contains(envelope.reference), let peer = sender(of: envelope, now: now) else { return nil }
        return try opened(envelope, from: peer)?.1
    }

    mutating func consume(_ envelope: Incoming, now: Int64) {
        guard acks.insert(envelope.reference).inserted, let peer = sender(of: envelope, now: now) else { return }
        guard !messages.contains(where: { !$0.outgoing && $0.peer == peer.id && $0.id == envelope.messageId }) else { return }
        do {
            guard let (newState, text) = try opened(envelope, from: peer) else { return }
            crypto = newState
            devices[peer.id] = peer.deviceId
            messages.append(ChatMessage(id: envelope.messageId, peer: peer.id, outgoing: false, text: text, status: .received))
        } catch {
            messages.append(ChatMessage(id: envelope.messageId, peer: peer.id, outgoing: false, text: nil, status: .unreadable))
        }
    }
}

func milliseconds(_ date: Date = Date()) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }
