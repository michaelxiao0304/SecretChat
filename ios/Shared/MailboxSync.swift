// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

@MainActor
struct MailboxSync {
    let service: Service

    func run(from initial: Profile, persist: (Profile) throws -> Void) async throws {
        var state = initial
        state.contacts = try await service.contacts()
        try persist(state)
        var boundary: Int64?
        repeat {
            let page = try await service.page(after: state.cursor, through: boundary)
            boundary = page.through
            let now = milliseconds()
            for envelope in page.envelopes { state.consume(envelope, now: now) }
            state.cursor = page.nextAfter
            if page.done { break }
        } while true
        // Publish only the complete snapshot, before issuing any acknowledgements.
        try persist(state)
        for reference in state.acks {
            do { try await service.acknowledge(reference) }
            catch let failure as Service.Failure where [404, 410].contains(failure.status) { }
            state.acks.remove(reference)
            try persist(state)
        }
        for item in state.outbox where state.canRetry(item) {
            do {
                let reference = try await service.submit(item)
                state.accept(item, reference: reference)
            } catch let failure as Service.Failure where failure.status == 409 {
                continue
            } catch let failure as Service.Failure where failure.status == 400 && item.expiresAtMs <= milliseconds() {
                state.outbox.removeAll { $0.messageId == item.messageId }
                if let index = state.messages.firstIndex(where: { $0.outgoing && $0.id == item.messageId }) {
                    state.messages[index].status = .unconfirmed
                }
            }
            try persist(state)
        }
        for index in state.messages.indices where state.messages[index].status == .accepted {
            guard let ref = state.messages[index].reference else { continue }
            do { state.messages[index].status = try await service.outcome(ref) }
            catch let error as Service.Failure where error.status == 404 { state.messages[index].status = .unconfirmed }
            try persist(state)
        }
    }
}
