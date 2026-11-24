// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import LibSignalClient

extension UUID {
    var wire: String { uuidString.lowercased() }

    fileprivate func address() throws -> ProtocolAddress {
        // Each account has one active protocol device. The service device UUID gates transport.
        try ProtocolAddress(name: wire, deviceId: 1)
    }
}

enum ClientError: LocalizedError {
    case invalidText, missingSession, invalidEnvelope, changedContact, invalidServer, missingAccount, unavailableStorage
    var errorDescription: String? {
        switch self {
        case .invalidText: return "请输入 1 至 16384 字节的非空文字。"
        case .missingSession: return "需要先取得联系人的预密钥。"
        case .invalidEnvelope: return "无法验证消息。"
        case .changedContact: return "联系人身份或设备已变化，投递已暂停。"
        case .invalidServer: return "请输入受信任的 HTTPS 服务地址。"
        case .missingAccount: return "请先注册设备。"
        case .unavailableStorage: return "无法访问本机安全存储。"
        }
    }
}

struct Packet {
    let id: UUID
    let ciphertext: Data
}

struct PublicPrekey: Codable {
    let id: UInt32
    let key: Data
    var signature: Data
}

struct OneTimePrekey: Codable, Equatable {
    let id: UInt32
    let material: Data
}

private struct PrekeyPool: Codable {
    var lastID: UInt32 = 0
    var records: [UInt32: Data] = [:]
    var pending: [OneTimePrekey] = []
}

struct PublicBundle {
    let registrationId: UInt32
    let identity: Data
    var signed: PublicPrekey
    let kyber: PublicPrekey

    var oneTime: OneTimePrekey?

    func signalBundle() throws -> PreKeyBundle {
        if let oneTime {
            return try PreKeyBundle(registrationId: registrationId, deviceId: 1,
                prekeyId: oneTime.id, prekey: PublicKey(oneTime.material),
                signedPrekeyId: signed.id, signedPrekey: PublicKey(signed.key), signedPrekeySignature: signed.signature,
                identity: IdentityKey(bytes: identity), kyberPrekeyId: kyber.id, kyberPrekey: KEMPublicKey(kyber.key),
                kyberPrekeySignature: kyber.signature)
        }
        return try PreKeyBundle(registrationId: registrationId, deviceId: 1,
                         signedPrekeyId: signed.id, signedPrekey: PublicKey(signed.key),
                         signedPrekeySignature: signed.signature, identity: IdentityKey(bytes: identity),
                         kyberPrekeyId: kyber.id, kyberPrekey: KEMPublicKey(kyber.key),
                         kyberPrekeySignature: kyber.signature)
    }
}

private struct TextContent: Codable {
    let id: UUID
    let sender: UUID
    let recipient: UUID
    let text: String

    func validate() throws {
        guard text.utf8.count <= 16_384, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ClientError.invalidText
        }
    }
}

// A call works on detached records. Only an authenticated result can publish its new state.
private final class WorkingStore: InMemorySignalProtocolStore {
    var records: [UInt32: Data] = [:]
    var usedBaseKeys: Set<Data> = []

    override func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard let bytes = records[id] else { throw SignalError.invalidKeyIdentifier("prekey unavailable") }
        return try PreKeyRecord(bytes: bytes)
    }

    override func removePreKey(id: UInt32, context: StoreContext) throws {
        records.removeValue(forKey: id)
    }

    override func markKyberPreKeyUsed(id: UInt32, signedPreKeyId: UInt32, baseKey: PublicKey,
                                      context: StoreContext) throws {
        // The prototype has one signed EC key and one last-resort Kyber key per device identity.
        guard usedBaseKeys.insert(baseKey.serialize()).inserted else { throw ClientError.invalidEnvelope }
        try super.markKyberPreKeyUsed(id: id, signedPreKeyId: signedPreKeyId, baseKey: baseKey, context: context)
    }
}

struct ClientState: Codable {
    private var pool: PrekeyPool?
    private let identity: Data
    private let registration: UInt32
    private let signedRecord: Data
    private let kyberRecord: Data
    private var usedBaseKeys: Set<Data> = []
    private var sessions: [UUID: Data] = [:]
    private var identities: [UUID: Data] = [:]
    private var received: [UUID: Set<UUID>] = [:]

    init() throws {
        let pair = IdentityKeyPair.generate()
        identity = pair.serialize()
        registration = UInt32.random(in: 1...0x3fff)
        let ec = PrivateKey.generate()
        let kem = KEMKeyPair.generate()
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        signedRecord = try SignedPreKeyRecord(id: 1, timestamp: now, privateKey: ec,
            signature: pair.privateKey.generateSignature(message: ec.publicKey.serialize())).serialize()
        kyberRecord = try KyberPreKeyRecord(id: 1, timestamp: now, keyPair: kem,
            signature: pair.privateKey.generateSignature(message: kem.publicKey.serialize())).serialize()
    }

    var pendingPrekeys: [OneTimePrekey] { pool?.pending ?? [] }

    mutating func preparePrekeys() throws -> [OneTimePrekey] {
        if !pendingPrekeys.isEmpty { return pendingPrekeys }
        var batch = pool ?? PrekeyPool()
        guard batch.lastID < UInt32.max - 16 else { throw ClientError.unavailableStorage }
        for _ in 0..<16 {
            batch.lastID += 1
            let key = PrivateKey.generate()
            let record = try PreKeyRecord(id: batch.lastID, privateKey: key)
            batch.records[batch.lastID] = record.serialize()
            batch.pending.append(OneTimePrekey(id: batch.lastID, material: key.publicKey.serialize()))
        }
        pool = batch
        return batch.pending
    }

    mutating func didUploadPrekeys() { pool?.pending.removeAll() }

    func publicBundle() throws -> PublicBundle {
        let ec = try SignedPreKeyRecord(bytes: signedRecord)
        let kem = try KyberPreKeyRecord(bytes: kyberRecord)
        return try PublicBundle(registrationId: registration, identity: IdentityKeyPair(bytes: identity).identityKey.serialize(),
            signed: PublicPrekey(id: ec.id, key: ec.publicKey().serialize(), signature: ec.signature),
            kyber: PublicPrekey(id: kem.id, key: kem.publicKey().serialize(), signature: kem.signature))
    }

    func hasSession(with peer: UUID) -> Bool { sessions[peer] != nil }
    func peerIdentity(_ peer: UUID) -> Data? { identities[peer] }

    private func workingStore(for peer: UUID) throws -> WorkingStore {
        let store = try WorkingStore(identity: IdentityKeyPair(bytes: identity), registrationId: registration)
        let address = try peer.address()
        let context = NullContext()
        if let key = identities[peer] {
            _ = try store.saveIdentity(IdentityKey(bytes: key), for: address, context: context)
        }
        if let session = sessions[peer] {
            try store.storeSession(SessionRecord(bytes: session), for: address, context: context)
        }
        let signed = try SignedPreKeyRecord(bytes: signedRecord)
        let kyber = try KyberPreKeyRecord(bytes: kyberRecord)
        try store.storeSignedPreKey(signed, id: signed.id, context: context)
        try store.storeKyberPreKey(kyber, id: kyber.id, context: context)
        store.records = pool?.records ?? [:]
        store.usedBaseKeys = usedBaseKeys
        return store
    }

    private mutating func commit(_ store: WorkingStore, peer: UUID) throws {
        let address = try peer.address()
        let session = try store.loadSession(for: address, context: NullContext())?.serialize()
        let identity = try store.identity(for: address, context: NullContext())?.serialize()
        guard let session, let identity else { throw ClientError.missingSession }
        sessions[peer] = session
        identities[peer] = identity
        pool?.records = store.records
        usedBaseKeys = store.usedBaseKeys
    }

    mutating func prepare(_ text: String, from me: UUID, to peer: UUID, bundle: PublicBundle?) throws -> Packet {
        let content = TextContent(id: UUID(), sender: me, recipient: peer, text: text)
        try content.validate()
        let store = try workingStore(for: peer)
        let remote = try peer.address(), local = try me.address()
        if !hasSession(with: peer) {
            guard let bundle else { throw ClientError.missingSession }
            try processPreKeyBundle(bundle.signalBundle(), for: remote, ourAddress: local,
                                    sessionStore: store, identityStore: store, context: NullContext())
        }
        let cipher = try signalEncrypt(message: JSONEncoder().encode(content), for: remote, localAddress: local,
                                       sessionStore: store, identityStore: store, context: NullContext())
        let bytes = Data([cipher.messageType.rawValue]) + cipher.serialize()
        guard bytes.count <= 65535 else { throw ClientError.invalidText }
        try commit(store, peer: peer)
        return Packet(id: content.id, ciphertext: bytes)
    }

    mutating func receive(_ packet: Packet, from peer: UUID, to me: UUID) throws -> String? {
        guard received[peer]?.contains(packet.id) != true else { return nil }
        let store = try workingStore(for: peer)
        let bytes = try decrypt(packet.ciphertext, using: store, peer: peer, me: me)
        let message = try JSONDecoder().decode(TextContent.self, from: bytes)
        guard message.id == packet.id, message.sender == peer, message.recipient == me else {
            throw ClientError.invalidEnvelope
        }
        try message.validate()
        try commit(store, peer: peer)
        received[peer, default: []].insert(packet.id)
        return message.text
    }

    private func decrypt(_ bytes: Data, using store: WorkingStore, peer: UUID, me: UUID) throws -> Data {
        guard let type = bytes.first else { throw ClientError.invalidEnvelope }
        let body = bytes.dropFirst()
        let remote = try peer.address(), local = try me.address()
        switch CiphertextMessage.MessageType(rawValue: type) {
        case .preKey:
            return try signalDecryptPreKey(message: PreKeySignalMessage(bytes: body), from: remote, localAddress: local,
                sessionStore: store, identityStore: store, preKeyStore: store,
                signedPreKeyStore: store, kyberPreKeyStore: store, context: NullContext())
        case .whisper:
            return try signalDecrypt(message: SignalMessage(bytes: body), from: remote, to: local,
                                     sessionStore: store, identityStore: store, context: NullContext())
        default:
            throw ClientError.invalidEnvelope
        }
    }
}
