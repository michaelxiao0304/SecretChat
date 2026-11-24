import CryptoKit
import XCTest
@testable import SecretChat

final class VaultTests: XCTestCase {
    func testCiphertextFileSurvivesRestartAndRejectsTamperingOrWrongKey() throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let key = SymmetricKey(size: .bits256)
        let vault = try Vault(directory: folder, key: key)
        XCTAssertNil(try vault.load())
        var state = try Profile()
        state.settings.showBody = true
        state.account = Account(accountId: UUID(), deviceId: UUID(), token: "confidential-bearer")
        try vault.save(state)
        let reopened = try Vault(directory: folder, key: key)
        XCTAssertEqual(try reopened.load()?.account?.token, "confidential-bearer")
        XCTAssertEqual(try reopened.load()?.settings.showBody, true)
        var disk = try Data(contentsOf: vault.file)
        XCTAssertNil(disk.range(of: Data("confidential-bearer".utf8)))
        XCTAssertThrowsError(try Vault(directory: folder, key: SymmetricKey(size: .bits256)).load())
        disk[disk.count - 1] ^= 1
        try disk.write(to: vault.file)
        XCTAssertThrowsError(try reopened.load())
    }
}
