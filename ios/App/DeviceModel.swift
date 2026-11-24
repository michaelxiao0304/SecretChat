// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SwiftUI
import UserNotifications
import UIKit

@MainActor
final class DeviceModel: ObservableObject {
    @Published private(set) var profile: Profile?
    @Published private(set) var busy = false
    @Published var issue: String?
    private var vault: Vault?
    private var pendingRefresh = false
    private var apnsToken: Data?
    private var boundToken: Data?

    func open() {
        guard vault == nil else { return }
        do {
            let storage = try Vault.shared(createKey: true)
            let saved = try storage.load()
            let initial = try saved ?? Profile()
            if saved == nil { try storage.save(initial) }
            vault = storage
            profile = initial
            issue = nil
        } catch { issue = error.localizedDescription }
    }

    func save(_ next: Profile) throws {
        guard let vault else { throw ClientError.unavailableStorage }
        try vault.save(next)
        profile = next
    }

    func service() throws -> Service {
        guard let server = profile?.server, let account = profile?.account else { throw ClientError.missingAccount }
        return try Service(base: server.absoluteString, token: account.token)
    }

    // All app writes run here. A wakeup during another operation schedules one further pass.
    func perform(_ action: () async throws -> Void) async {
        guard !busy else { return }
        busy = true
        issue = nil
        do { try await action() } catch { issue = error.localizedDescription }
        busy = false
        if pendingRefresh {
            pendingRefresh = false
            await refresh()
        }
    }

    func refresh() async {
        guard profile?.account != nil else { return }
        guard !busy else { pendingRefresh = true; return }
        await perform { try await synchronize() }
    }

    private func synchronize() async throws {
        guard let current = profile else { throw ClientError.missingAccount }
        let api = try service()
        try await MailboxSync(service: api).run(from: current, persist: save)
        if let token = apnsToken, token != boundToken {
            try await api.bind(token: token)
            boundToken = token
        }
    }

    func register(server: String, email: String, invite: String, challenge: UUID?, code: String) async throws {
        guard var next = profile, next.account == nil else { throw ClientError.unavailableStorage }
        let api = try Service(base: server)
        next.account = try await api.register(email: email, invite: invite, challenge: challenge, code: code,
                                              bundle: next.crypto.publicBundle())
        next.server = api.base
        try save(next)
        pendingRefresh = true
    }

    func send(_ text: String, to peer: Contact) async throws {
        guard var next = profile else { throw ClientError.missingAccount }
        let bundle = next.crypto.hasSession(with: peer.id) ? nil : try await service().bundle(for: peer)
        _ = try next.queue(text, for: peer, bundle: bundle, now: milliseconds())
        try save(next)
        pendingRefresh = true
    }

    func setPreview(_ enabled: Bool) async {
        await perform {
            guard var next = profile else { throw ClientError.unavailableStorage }
            next.settings.showBody = enabled
            try save(next)
        }
    }

    func tokenChanged(_ data: Data) async {
        apnsToken = data
        await refresh()
    }

    func listen() async {
        while !Task.isCancelled, let api = try? service() {
            await refresh()
            do { try await api.listen { await self.refresh() } } catch {}
            do { try await Task.sleep(for: .seconds(3)) } catch { return }
        }
    }
}

final class PushDelegate: NSObject, UIApplicationDelegate, ObservableObject {
    @Published var token: Data?
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        token = deviceToken
    }
}
