// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

@MainActor
struct MailboxSync {
    let service: Service

    func run(from initial: Profile, persist: (Profile) throws -> Void) async throws {
        var state = initial
        let contacts = try await service.contacts()
        if state.contacts != contacts {
            state.contacts = contacts
            try persist(state)
        }
        Diagnostics.event(.mailbox, "directory.refresh", .ok, count: state.contacts.count)
        try await receive(into: &state, persist: persist)
        try await acknowledge(in: &state, persist: persist)
        try await submit(from: &state, persist: persist)
        try await reconcile(in: &state, persist: persist)
    }

    private func receive(into state: inout Profile, persist: (Profile) throws -> Void) async throws {
        let initialCursor = state.cursor
        var boundary: Int64?
        repeat {
            let page = try await service.page(after: state.cursor, through: boundary)
            Diagnostics.event(.mailbox, "inbox.page", .ok, count: page.envelopes.count)
            boundary = page.through
            let now = milliseconds()
            for envelope in page.envelopes { state.consume(envelope, now: now) }
            state.cursor = page.nextAfter
            if page.done { break }
        } while true
        // Publish only the complete snapshot, before issuing any acknowledgements.
        if state.cursor != initialCursor { try persist(state) }
        Diagnostics.event(.mailbox, "inbox.committed", .ok, count: state.acks.count)
    }

    private func acknowledge(in state: inout Profile, persist: (Profile) throws -> Void) async throws {
        for reference in state.acks {
            do { try await service.acknowledge(reference) }
            catch let failure as Service.Failure where [404, 410].contains(failure.status) {
                Diagnostics.failure(.mailbox, "ack.retired", failure, phase: .skip)
            }
            state.acks.remove(reference)
            try persist(state)
        }
    }

    private func submit(from state: inout Profile, persist: (Profile) throws -> Void) async throws {
        for item in state.outbox {
            let access = state.retryAccess(for: item)
            guard access == .available else {
                Diagnostics.event(.mailbox, "outbox.paused", .skip, reason: access.diagnosticReason)
                continue
            }
            do {
                let reference = try await service.submit(item)
                state.accept(item, reference: reference)
            } catch let failure as Service.Failure where failure.status == 409 {
                Diagnostics.failure(.mailbox, "outbox.conflict", failure, phase: .skip)
                continue
            } catch let failure as Service.Failure where failure.status == 400 && item.expiresAtMs <= milliseconds() {
                state.outbox.removeAll { $0.messageId == item.messageId }
                if let index = state.messages.firstIndex(where: { $0.outgoing && $0.id == item.messageId }) {
                    state.messages[index].status = .unconfirmed
                }
            }
            try persist(state)
        }
    }

    private func reconcile(in state: inout Profile, persist: (Profile) throws -> Void) async throws {
        for index in state.messages.indices where state.messages[index].status == .accepted {
            guard let ref = state.messages[index].reference else { continue }
            do {
                let outcome = try await service.outcome(ref)
                guard outcome != .accepted else { continue }
                state.messages[index].status = outcome
            }
            catch let error as Service.Failure where error.status == 404 { state.messages[index].status = .unconfirmed }
            try persist(state)
        }
    }
}
