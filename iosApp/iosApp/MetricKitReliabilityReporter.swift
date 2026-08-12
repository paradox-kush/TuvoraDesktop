import Foundation
import MetricKit
import PostHog

/// Reports Apple's delayed reliability diagnostics without exporting diagnostic payloads.
///
/// MetricKit payloads contain call-stack trees and signpost data, which can carry paths or
/// other app context. This subscriber deliberately records only bounded aggregate metadata.
@available(iOS 14.0, *)
final class MetricKitReliabilityReporter: NSObject, MXMetricManagerSubscriber {
    static let shared = MetricKitReliabilityReporter()

    private let stateLock = NSLock()
    private var isStarted = false

    private override init() {
        super.init()
    }

    func start() {
        stateLock.lock()
        defer { stateLock.unlock() }

        guard !isStarted else { return }
        isStarted = true
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            let windowHours = Int(
                max(0, min(168, payload.timeStampEnd.timeIntervalSince(payload.timeStampBegin) / 3_600)).rounded()
            )

            captureCrashes(payload.crashDiagnostics, windowHours: windowHours)
            capture(payload.hangDiagnostics, category: "hang", windowHours: windowHours)
            capture(payload.cpuExceptionDiagnostics, category: "cpu_exception", windowHours: windowHours)
            capture(payload.diskWriteExceptionDiagnostics, category: "disk_write_exception", windowHours: windowHours)
        }
    }

    private func captureCrashes(_ diagnostics: [MXCrashDiagnostic]?, windowHours: Int) {
        guard let diagnostics, !diagnostics.isEmpty else { return }

        let grouped = Dictionary(grouping: diagnostics) { diagnostic in
            CrashKey(
                appVersion: diagnostic.applicationVersion,
                terminationReason: safeLabel(diagnostic.terminationReason)
            )
        }
        for (key, crashes) in grouped {
            PostHogSDK.shared.capture(
                "ios_metric_diagnostic",
                properties: [
                    "diagnostic_type": "crash",
                    "diagnostic_count": crashes.count,
                    "diagnostic_app_version": key.appVersion,
                    "termination_reason": key.terminationReason,
                    "payload_window_hours": windowHours,
                    "diagnostic_source": "metrickit"
                ]
            )
        }
    }

    private func capture<Diagnostic: MXDiagnostic>(
        _ diagnostics: [Diagnostic]?,
        category: String,
        windowHours: Int
    ) {
        guard let diagnostics, !diagnostics.isEmpty else { return }

        // A delivered payload may span an app update, so preserve the affected build while
        // aggregating. No diagnostic body, stack, URL, signpost, or user data is inspected.
        let countsByVersion = Dictionary(grouping: diagnostics, by: { $0.applicationVersion })
            .mapValues(\.count)

        for (appVersion, count) in countsByVersion {
            PostHogSDK.shared.capture(
                "ios_metric_diagnostic",
                properties: [
                    "diagnostic_type": category,
                    "diagnostic_count": count,
                    "diagnostic_app_version": appVersion,
                    "payload_window_hours": windowHours,
                    "diagnostic_source": "metrickit"
                ]
            )
        }
    }

    private struct CrashKey: Hashable {
        let appVersion: String
        let terminationReason: String
    }

    private func safeLabel(_ value: String?) -> String {
        let raw = value?.lowercased() ?? "unknown"
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "_-"))
        let normalized = raw.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" }
        return String(normalized).prefix(64).description
    }
}
