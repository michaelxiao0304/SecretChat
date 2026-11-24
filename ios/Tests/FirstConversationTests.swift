// SPDX-License-Identifier: AGPL-3.0-only
import XCTest
import UIKit
@testable import SecretChat

final class FirstConversationTests: XCTestCase {
    private let base = "https://service.test/secretchat"
    private let id = UUID()

    @MainActor
    func testSelectedArtworkIsAvailableToTheInstalledApplication() throws {
        let image = try XCTUnwrap(InvitationStyle.letter)
        XCTAssertGreaterThan(image.size.width, image.size.height)
    }

    func testRegistrationRequiresRealInviteValidAddressAndCurrentEmailChallenge() {
        var draft = RegistrationDraft(server: base)
        draft.invite = "  Case-Sensitive_token-9  "
        draft.email = " user@example.test "
        XCTAssertEqual(draft.inviteCode, "Case-Sensitive_token-9")
        XCTAssertEqual(draft.address, "user@example.test")
        XCTAssertTrue(draft.canRequestCode)
        XCTAssertFalse(draft.canRegister)
        draft.receiveChallenge(id)
        for invalid in ["", "12345", "1234567", "１２３４５６", "12345x"] {
            draft.code = invalid
            XCTAssertFalse(draft.canRegister, invalid)
        }
        draft.code = "012345"
        XCTAssertTrue(draft.canRegister)
        draft.invite = "   "
        XCTAssertFalse(draft.canRegister)
    }

    func testChangingRegistrationContextInvalidatesChallengeAndCode() {
        var original = RegistrationDraft(server: base)
        original.email = "user@example.test"
        original.invite = "token"
        original.receiveChallenge(id)
        original.code = "123456"
        for change: (inout RegistrationDraft) -> Void in [
            { $0.server = "https://other.test" }, { $0.email = "other@example.test" }, { $0.development = true }
        ] {
            var changed = original
            change(&changed)
            XCTAssertNil(changed.challenge)
            XCTAssertEqual(changed.code, "")
        }
        original.receiveChallenge(UUID())
        XCTAssertEqual(original.code, "")
    }

    func testDevelopmentRegistrationStillChecksServiceEmailAndInvite() {
        var draft = RegistrationDraft(server: "")
        draft.development = true
        draft.invite = "token"
        draft.email = "user@example.test"
        for invalid in ["", "http://service.test", "https://user:password@service.test"] {
            draft.server = invalid
            XCTAssertFalse(draft.canRegister)
        }
        draft.server = base
        for invalid in ["", "a@", "name @example.test", "name@host", "a<>@example.test"] {
            draft.email = invalid
            XCTAssertFalse(draft.canRegister)
        }
        draft.email = "user@example.test"
        XCTAssertTrue(draft.canRegister)
        draft.development = false
        XCTAssertFalse(draft.canRegister)
    }

    func testSharedInvitationOpensOnUnconfiguredInstallAndCanUseBareCode() throws {
        let ticket = SharedInvitation(code: "aB_9-T", server: try Service.validatedBase(base))
        var draft = RegistrationDraft(server: "")
        XCTAssertEqual(draft.paste(ticket.text), .accepted)
        XCTAssertEqual(draft.inviteCode, "aB_9-T")
        XCTAssertEqual(draft.serviceRoot, base)
        XCTAssertTrue(draft.hasService)
        XCTAssertEqual(draft.paste("newToken"), .accepted)
        XCTAssertEqual(draft.inviteCode, "newToken")
        XCTAssertFalse(draft.hasService)
    }

    func testPastingCannotSilentlyChangeConfiguredServiceOrUseMalformedTicket() throws {
        let ticket = SharedInvitation(code: "myToken", server: try Service.validatedBase(base))
        var draft = RegistrationDraft(server: "https://tenant.example.test")
        draft.invite = "original"
        XCTAssertEqual(draft.paste(ticket.text), .differentService)
        XCTAssertEqual(draft.inviteCode, "original")
        XCTAssertEqual(draft.serviceRoot, "https://tenant.example.test")
        for text in ["", "one\ntwo", ticket.text.replacingOccurrences(of: "https:", with: "http:")] {
            XCTAssertEqual(draft.paste(text), .invalid)
            XCTAssertEqual(draft.inviteCode, "original")
        }
        draft.server = base + "/"
        XCTAssertEqual(draft.paste(ticket.text), .accepted)
        XCTAssertEqual(draft.inviteCode, "myToken")
        draft.email = "demo@example.test"
        draft.development = true
        XCTAssertTrue(draft.canRegister)
        draft.server = "https://other.test"
        XCTAssertFalse(draft.canRequestCode)
        XCTAssertFalse(draft.canRegister)
        draft.invite = "token-for-new-service"
        XCTAssertTrue(draft.canRegister)
    }

    func testDisplayedContactStateMatchesSendAndRetryEligibility() throws {
        var state = try Profile()
        state.account = Account(accountId: UUID(), deviceId: UUID(), token: "test")
        let keys = try ClientState().publicBundle()
        let original = Contact(accountId: id, deviceId: UUID(), identityKey: keys.identity, blocked: false)
        state.contacts = [original]
        let item = try state.queue("test", for: original, bundle: keys, now: 0)
        let cases: [(Contact?, ContactAccess)] = [
            (original, .available), (nil, .missing),
            (Contact(accountId: id, deviceId: UUID(), identityKey: keys.identity, blocked: false), .identityChanged),
            (Contact(accountId: id, deviceId: original.deviceId, identityKey: Data([1]), blocked: false), .identityChanged),
            (Contact(accountId: id, deviceId: original.deviceId, identityKey: keys.identity, blocked: true), .blocked)
        ]
        for (record, expected) in cases {
            state.contacts = record.map { [$0] } ?? []
            XCTAssertEqual(state.access(to: record), expected)
            XCTAssertEqual(state.canRetry(item), expected == .available)
        }
        XCTAssertEqual(state.messages.last?.status, .pending)
    }

    func testReceiptsShowOnlyExistingEvidenceAndRespectPausedRetries() {
        XCTAssertEqual(Delivery.pending.label, "本机待提交")
        XCTAssertEqual(Delivery.accepted.label, "服务端已接收")
        XCTAssertEqual(Delivery.received.label, "对方设备已收到")
        XCTAssertTrue(Delivery.pending.explanation(access: .available).contains("尝试提交"))
        for restriction in [ContactAccess.identityChanged, .blocked, .missing] {
            XCTAssertTrue(Delivery.pending.explanation(access: restriction).contains("暂停重试"))
            XCTAssertEqual(Delivery.received.explanation(access: restriction), Delivery.received.explanation(access: .available))
        }
        XCTAssertTrue(Delivery.received.explanation(access: .available).contains("阅读情况未知"))
        XCTAssertEqual(Set([Delivery.pending, .accepted, .received, .unreadable, .unconfirmed, .expired].map(\.label)).count, 6)
    }
}
