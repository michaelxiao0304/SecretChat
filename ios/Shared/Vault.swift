// SPDX-License-Identifier: AGPL-3.0-only
import CryptoKit
import Foundation
import Security

struct Vault {
    let file: URL
    private let key: SymmetricKey

    init(directory: URL, key: SymmetricKey) throws {
        var folder = directory
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var flags = URLResourceValues()
        flags.isExcludedFromBackup = true
        try folder.setResourceValues(flags)
        self.file = folder.appending(path: "device.sealed")
        self.key = key
    }

    private init(file: URL, key: SymmetricKey) {
        self.file = file
        self.key = key
    }

    static func shared(createKey: Bool) throws -> Vault {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "SecretChatGroup") as? String,
              let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else {
            throw ClientError.unavailableStorage
        }
        let directory = container.appending(path: "Device", directoryHint: .isDirectory)
        let file = directory.appending(path: "device.sealed")
        let mayCreate = createKey && !FileManager.default.fileExists(atPath: file.path)
        let key = try deviceKey(group: group, create: mayCreate)
        if createKey { return try Vault(directory: directory, key: key) }
        return Vault(file: file, key: key)
    }

    private static func deviceKey(group: String, create: Bool) throws -> SymmetricKey {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "SecretChat.DeviceVault", kSecAttrAccount as String: "v1",
            kSecAttrAccessGroup as String: group]
        var result: CFTypeRef?
        var lookup = query
        lookup[kSecReturnData as String] = true
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data, data.count == 32 {
            return SymmetricKey(data: data)
        }
        guard status == errSecItemNotFound, create else {
            Diagnostics.event(.storage, "keychain.read", .failed, reason: .unavailable, code: Int(status))
            throw ClientError.unavailableStorage
        }
        let key = SymmetricKey(size: .bits256)
        var item = query
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        item[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        let added = SecItemAdd(item as CFDictionary, nil)
        switch added {
        case errSecSuccess: return key
        case errSecDuplicateItem: return try deviceKey(group: group, create: false)
        default:
            Diagnostics.event(.storage, "keychain.create", .failed, reason: .unavailable, code: Int(added))
            throw ClientError.unavailableStorage
        }
    }

    func load() throws -> Profile? {
        let data: Data
        do { data = try Data(contentsOf: file) }
        catch let error as CocoaError where error.code == .fileReadNoSuchFile { return nil }
        let bytes = try AES.GCM.open(AES.GCM.SealedBox(combined: data), using: key)
        return try JSONDecoder().decode(Profile.self, from: bytes)
    }

    func save(_ profile: Profile) throws {
        do { try write(profile) } catch {
            Diagnostics.failure(.storage, "vault.commit", error)
            throw error
        }
    }

    private func write(_ profile: Profile) throws {
        let sealed = try AES.GCM.seal(JSONEncoder().encode(profile), using: key)
        guard let data = sealed.combined else { throw ClientError.unavailableStorage }
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
