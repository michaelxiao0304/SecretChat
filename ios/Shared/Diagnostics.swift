// SPDX-License-Identifier: AGPL-3.0-only
import CryptoKit
import Foundation
import LibSignalClient
import OSLog

enum DiagnosticArea: String { case lifecycle, account, storage, protocolState, mailbox, network, push }
enum DiagnosticPhase: String { case begin, ok, failed, skip, retry, fallback }
enum DiagnosticReason: String {
    case http, network, cancelled, decoding, file, crypto, invalidData, identityChanged
    case unavailable, ineligible, timeout, duplicate, unknown
    case unknownSender, missingContact, blocked, deviceChanged, expired
}

// Only stage literals, local trace IDs, closed enums and numbers can reach the log sink.
struct DiagnosticRecord {
    let area: DiagnosticArea
    let stage: StaticString
    let phase: DiagnosticPhase
    let trace: String
    let reason: DiagnosticReason?
    let requestID: UUID?
    let code: Int?
    let count: Int?
    let elapsedMS: UInt64?

    var line: String {
        var fields = ["trace=" + trace, "stage=" + stage.description, "phase=" + phase.rawValue]
        if let requestID { fields.append("requestId=" + requestID.wire) }
        if let reason { fields.append("reason=" + reason.rawValue) }
        if let code { fields.append("code=" + String(code)) }
        if let count { fields.append("count=" + String(count)) }
        if let elapsedMS { fields.append("elapsed_ms=" + String(elapsedMS)) }
        return fields.joined(separator: " ")
    }
}

enum Diagnostics {
    static let subsystem = "chat.secretchat.ios"
    @TaskLocal static var traceID: UUID?
    @TaskLocal static var observer: ((DiagnosticRecord) -> Void)?

    static func event(_ area: DiagnosticArea, _ stage: StaticString, _ phase: DiagnosticPhase,
                      reason: DiagnosticReason? = nil, code: Int? = nil, requestID: UUID? = nil, count: Int? = nil,
                      elapsedMS: UInt64? = nil) {
        let record = DiagnosticRecord(area: area, stage: stage, phase: phase, trace: (traceID ?? UUID()).wire,
                                     reason: reason, requestID: requestID, code: code, count: count, elapsedMS: elapsedMS)
        if let observer { observer(record); return }
        let logger = Logger(subsystem: subsystem, category: area.rawValue)
        switch phase {
        case .failed: logger.error("\(record.line, privacy: .public)")
        case .retry, .fallback: logger.notice("\(record.line, privacy: .public)")
        default: logger.info("\(record.line, privacy: .public)")
        }
    }

    static func failure(_ area: DiagnosticArea, _ stage: StaticString, _ error: Error,
                        phase: DiagnosticPhase = .failed, elapsedMS: UInt64? = nil) {
        let reason: DiagnosticReason
        var code: Int?
        switch error {
        case let failure as Service.Failure: reason = .http; code = failure.status
        case let failure as URLError:
            reason = failure.code == .cancelled ? .cancelled : .network
            code = failure.errorCode
        case is CancellationError: reason = .cancelled
        case is DecodingError: reason = .decoding
        case let failure as CocoaError: reason = .file; code = failure.errorCode
        case is SignalError, is CryptoKitError: reason = .crypto
        case let restriction as ContactAccess: reason = restriction.diagnosticReason ?? .ineligible
        case ClientError.changedContact: reason = .identityChanged
        case ClientError.unavailableStorage, ClientError.missingAccount: reason = .unavailable
        case is ClientError: reason = .invalidData
        default: reason = .unknown
        }
        event(area, stage, reason == .cancelled ? .skip : phase, reason: reason, code: code,
              requestID: (error as? Service.Failure)?.requestID, elapsedMS: elapsedMS)
    }

    static func span<T>(_ area: DiagnosticArea, _ stage: StaticString,
                        _ work: () async throws -> T) async rethrows -> T {
        try await $traceID.withValue(traceID ?? UUID()) {
            let start = DispatchTime.now().uptimeNanoseconds
            func elapsed() -> UInt64 { (DispatchTime.now().uptimeNanoseconds - start) / 1_000_000 }
            event(area, stage, .begin)
            do {
                let value = try await work()
                event(area, stage, .ok, elapsedMS: elapsed())
                return value
            } catch {
                failure(area, stage, error, elapsedMS: elapsed())
                throw error
            }
        }
    }
}
