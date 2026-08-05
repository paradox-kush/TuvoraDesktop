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

            capture(payload.crashDiagnostics, category: "crash", windowHours: windowHours)
            capture(payload.hangDiagnostics, category: "hang", windowHours: windowHours)
            capture(payload.cpuExceptionDiagnostics, category: "cpu_exception", windowHours: windowHours)
            capture(payload.diskWriteExceptionDiagnostics, category: "disk_write_exception", windowHours: windowHours)
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
}
