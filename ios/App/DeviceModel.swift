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

    private let openVault: () throws -> Vault
    private let makeService: (String, String?) throws -> Service

    init(openVault: @escaping () throws -> Vault = { try Vault.shared(createKey: true) },
         makeService: @escaping (String, String?) throws -> Service = { try Service(base: $0, token: $1) }) {
        self.openVault = openVault
        self.makeService = makeService
    }

    func open() {
        guard vault == nil else { return }
        Diagnostics.$traceID.withValue(UUID()) { openStorage() }
    }

    private func openStorage() {
        Diagnostics.event(.lifecycle, "app.open", .begin)
        do {
            let storage = try openVault()
            let saved = try storage.load()
            let initial = try saved ?? Profile()
            if saved == nil { try storage.save(initial) }
            vault = storage
            profile = initial
            issue = nil
            Diagnostics.event(.lifecycle, "app.open", .ok)
        } catch {
            Diagnostics.failure(.storage, "app.open", error)
            issue = error.localizedDescription
        }
    }

    func save(_ next: Profile) throws {
        guard let vault else { throw ClientError.unavailableStorage }
        try vault.save(next)
        profile = next
    }

    func service() throws -> Service {
        guard let server = profile?.server, let account = profile?.account else { throw ClientError.missingAccount }
        return try makeService(server.absoluteString, account.token)
    }

    // All app writes run here. A wakeup during another operation schedules one further pass.
    func perform(_ action: () async throws -> Void) async {
        guard !busy else { return }
        busy = true
        issue = nil
        do { try await Diagnostics.span(.lifecycle, "device.operation", action) } catch { issue = error.localizedDescription }
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
        try await Diagnostics.span(.mailbox, "sync") {
            try await MailboxSync(service: api).run(from: current, persist: save)
        }
        try await replenish(using: api)
        if let token = apnsToken, token != boundToken {
            try await api.bind(token: token)
            boundToken = token
        }
    }

    private func replenish(using api: Service) async throws {
        guard var state = profile else { throw ClientError.missingAccount }
        if state.crypto.pendingPrekeys.isEmpty {
            guard try await api.remainingPrekeys() < 4 else { return }
        }
        let keys = try state.crypto.preparePrekeys()
        try save(state)
        try await api.upload(prekeys: keys)
        state.crypto.didUploadPrekeys()
        try save(state)
        Diagnostics.event(.protocolState, "prekeys.replenished", .ok, count: keys.count)
    }

    func register(server: String, email: String, invite: String, challenge: UUID?, code: String) async throws {
        guard var next = profile, next.account == nil else { throw ClientError.unavailableStorage }
        let api = try makeService(server, nil)
        next.account = try await api.register(email: email, invite: invite, challenge: challenge, code: code,
                                              bundle: next.crypto.publicBundle())
        next.server = api.base
        try save(next)
        Diagnostics.event(.account, "device.registered", .ok)
        pendingRefresh = true
    }

    func send(_ text: String, to peer: Contact) async throws {
        guard var next = profile else { throw ClientError.missingAccount }
        let bundle = next.crypto.hasSession(with: peer.id) ? nil : try await service().bundle(for: peer)
        _ = try next.queue(text, for: peer, bundle: bundle, now: milliseconds())
        try save(next)
        Diagnostics.event(.mailbox, "outbox.committed", .ok, count: next.outbox.count)
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
            do {
                try await Diagnostics.span(.network, "socket.listen") {
                    try await api.listen { await self.refresh() }
                }
            } catch {
                guard !Task.isCancelled else { return }
                Diagnostics.failure(.network, "socket.reconnect", error, phase: .retry)
            }
            do { try await Task.sleep(for: .seconds(3)) } catch { return }
        }
    }
}

final class PushDelegate: NSObject, UIApplicationDelegate, ObservableObject {
    @Published var token: Data?
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Diagnostics.event(.push, "apns.registration", .ok)
        token = deviceToken
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Diagnostics.failure(.push, "apns.registration", error)
    }
}
