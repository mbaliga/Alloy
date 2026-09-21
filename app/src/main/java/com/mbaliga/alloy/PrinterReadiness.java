package com.mbaliga.alloy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure, user-facing print gate evaluation.
 *
 * The transport and durable job store remain authoritative for a live
 * transaction. This class deliberately evaluates only the prerequisites that
 * must be true before a new physical send is exposed, so the Activity can
 * explain a blocked action without probing the printer or changing state.
 */
public final class PrinterReadiness {
    public enum State { PASS, BLOCKED }

    public static final class Check {
        public final String label;
        public final State state;
        public final String detail;

        private Check(String label, State state, String detail) {
            this.label = label;
            this.state = state;
            this.detail = detail;
        }

        public boolean passed() { return state == State.PASS; }
    }

    public static final class Report {
        private final ArrayList<Check> checks;

        private Report(ArrayList<Check> checks) {
            this.checks = checks;
        }

        public List<Check> checks() {
            return Collections.unmodifiableList(checks);
        }

        public boolean canSend() {
            for (Check check : checks) if (!check.passed()) return false;
            return true;
        }

        public int passedCount() {
            int count = 0;
            for (Check check : checks) if (check.passed()) count++;
            return count;
        }

        public String summary() {
            return canSend()
                    ? "READY · all physical-print gates passed"
                    : "BLOCKED · " + (checks.size() - passedCount()) + " gate"
                            + (checks.size() - passedCount() == 1 ? "" : "s") + " need review";
        }
    }

    private PrinterReadiness() { }

    /**
     * Evaluate a complete physical-send prerequisite set without performing
     * network I/O. Every argument is an already-validated fact from the
     * current Activity/session state.
     */
    public static Report evaluate(boolean nativeEngineIncluded,
                                  boolean nativeEngineVerified,
                                  boolean profileLoaded,
                                  boolean profileVerified,
                                  boolean sliceReady,
                                  boolean sliceVerified,
                                  boolean artifactReady,
                                  boolean printerPaired,
                                  boolean a1MiniModelConfirmed,
                                  boolean certificatePinned,
                                  boolean recoveryClear) {
        return evaluateInternal(nativeEngineIncluded, nativeEngineVerified, profileLoaded, profileVerified,
                sliceReady, sliceVerified, artifactReady, printerPaired, a1MiniModelConfirmed,
                certificatePinned, recoveryClear, true, null, false);
    }

    /**
     * Full physical-send evaluation. The support gate is intentionally a
     * separate input: general engine verification must not imply Bambu support
     * parity when a recipe requests automatic/tree supports.
     */
    public static Report evaluate(boolean nativeEngineIncluded,
                                  boolean nativeEngineVerified,
                                  boolean profileLoaded,
                                  boolean profileVerified,
                                  boolean sliceReady,
                                  boolean sliceVerified,
                                  boolean artifactReady,
                                  boolean printerPaired,
                                  boolean a1MiniModelConfirmed,
                                  boolean certificatePinned,
                                  boolean recoveryClear,
                                  boolean supportParityReady,
                                  String supportParityDetail) {
        return evaluateInternal(nativeEngineIncluded, nativeEngineVerified, profileLoaded, profileVerified,
                sliceReady, sliceVerified, artifactReady, printerPaired, a1MiniModelConfirmed,
                certificatePinned, recoveryClear, supportParityReady, supportParityDetail, true);
    }

    private static Report evaluateInternal(boolean nativeEngineIncluded,
                                           boolean nativeEngineVerified,
                                           boolean profileLoaded,
                                           boolean profileVerified,
                                           boolean sliceReady,
                                           boolean sliceVerified,
                                           boolean artifactReady,
                                           boolean printerPaired,
                                           boolean a1MiniModelConfirmed,
                                           boolean certificatePinned,
                                           boolean recoveryClear,
                                           boolean supportParityReady,
                                           String supportParityDetail,
                                           boolean includeSupportParityCheck) {
        ArrayList<Check> checks = new ArrayList<>();
        add(checks, "Native engine", nativeEngineIncluded && nativeEngineVerified,
                nativeEngineIncluded
                        ? (nativeEngineVerified ? "Verified build" : "Included, but not promoted for physical printing")
                        : "Not included in this APK");
        add(checks, "A1 Mini profile", profileLoaded && profileVerified,
                profileLoaded
                        ? (profileVerified ? "Verified profile" : "Loaded, but not approved for physical printing")
                        : "No packaged profile loaded");
        add(checks, "Slice", sliceReady && sliceVerified,
                sliceReady
                        ? (sliceVerified ? "Verified native result" : "Result is not marked engine-verified")
                        : "Slice a model first");
        if (includeSupportParityCheck) {
            add(checks, "Support parity", supportParityReady,
                    supportParityDetail.trim().length() == 0
                            ? (supportParityReady ? "Support parity is covered" : "Production support parity is not available")
                            : supportParityDetail);
        }
        add(checks, "Printer package", artifactReady, artifactReady
                ? "Validated .gcode.3mf is staged"
                : "Validated .gcode.3mf is not staged");
        add(checks, "Printer pairing", printerPaired, printerPaired
                ? "Target credentials are present"
                : "Pair an A1 Mini before sending");
        add(checks, "Printer model", a1MiniModelConfirmed, a1MiniModelConfirmed
                ? "N1 / A1 Mini confirmed"
                : "Confirm the discovered model is N1 (A1 Mini)");
        add(checks, "Certificate pin", certificatePinned, certificatePinned
                ? "Leaf SHA-256 pin configured"
                : "Read and save the printer's leaf SHA-256 fingerprint");
        add(checks, "Recovery lock", recoveryClear, recoveryClear
                ? "No unconfirmed printer transaction"
                : "Review the unconfirmed transaction before sending again");
        return new Report(checks);
    }

    private static void add(ArrayList<Check> checks, String label, boolean passed, String detail) {
        checks.add(new Check(label, passed ? State.PASS : State.BLOCKED, detail));
    }
}
