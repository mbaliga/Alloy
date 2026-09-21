package com.mbaliga.alloy;

/**
 * Pure state transition for a single paired Bambu print session.
 *
 * The network transport owns sockets and deadlines; this class owns only the
 * evidence rules for a telemetry packet. Keeping the transition pure makes a
 * recorded or synthetic printer transcript replayable in tests without
 * treating that transcript as physical A1 Mini validation.
 */
final class PrinterTelemetryReducer {
    enum ControlRequest { NONE, PAUSE, RESUME }

    enum Event {
        IGNORE,
        RUNNING,
        PAUSED,
        RESUMED,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    static final class Decision {
        final Event event;
        final boolean accepted;
        final boolean running;
        final boolean matchingJobSeen;
        final ControlRequest controlRequest;

        private Decision(Event event, boolean accepted, boolean running,
                         boolean matchingJobSeen, ControlRequest controlRequest) {
            this.event = event;
            this.accepted = accepted;
            this.running = running;
            this.matchingJobSeen = matchingJobSeen;
            this.controlRequest = controlRequest;
        }
    }

    private PrinterTelemetryReducer() { }

    /** Apply exactly one report to the current session evidence. */
    static Decision observe(String remoteName, String payload, boolean accepted,
                            boolean running, boolean matchingJobSeen,
                            boolean cancelRequested, ControlRequest controlRequest) {
        boolean valid = BambuLanTransport.telemetrySummary(payload).length() > 0;
        if (!valid) return decision(Event.IGNORE, accepted, running, matchingJobSeen, controlRequest);

        boolean mentionsJob = BambuLanTransport.telemetryMentionsJob(payload, remoteName);
        boolean nextMatchingJobSeen = matchingJobSeen || mentionsJob;
        if (cancelRequested && BambuLanTransport.telemetryConfirmsCancellation(
                payload, remoteName, accepted, nextMatchingJobSeen)) {
            return decision(Event.CANCELLED, accepted, running, nextMatchingJobSeen, ControlRequest.NONE);
        }
        if (!mentionsJob) return decision(Event.IGNORE, accepted, running, nextMatchingJobSeen, controlRequest);

        if (controlRequest == ControlRequest.PAUSE
                && BambuLanTransport.telemetryHasState(payload, "pause", "paused")) {
            return decision(Event.PAUSED, accepted, running, nextMatchingJobSeen, ControlRequest.NONE);
        }
        if (controlRequest == ControlRequest.RESUME
                && BambuLanTransport.telemetryHasState(payload, "prepare", "running")) {
            return decision(Event.RESUMED, true, true, nextMatchingJobSeen, ControlRequest.NONE);
        }
        if (BambuLanTransport.telemetryHasState(payload, "failed", "error", "cancelled")) {
            if (BambuLanTransport.telemetryHasState(payload, "cancelled") && accepted && !running)
                return decision(Event.CANCELLED, accepted, running, nextMatchingJobSeen, ControlRequest.NONE);
            return decision(Event.FAILED, accepted, running, nextMatchingJobSeen, ControlRequest.NONE);
        }
        if (BambuLanTransport.telemetryHasState(payload, "finish", "finished", "completed")) {
            return accepted
                    ? decision(Event.COMPLETED, true, running, nextMatchingJobSeen, ControlRequest.NONE)
                    : decision(Event.IGNORE, accepted, running, nextMatchingJobSeen, controlRequest);
        }
        if (BambuLanTransport.telemetryHasState(payload, "prepare", "running")) {
            boolean nowRunning = running || BambuLanTransport.telemetryHasState(payload, "running");
            return decision(nowRunning && !running ? Event.RUNNING : Event.IGNORE,
                    true, nowRunning, nextMatchingJobSeen, controlRequest);
        }
        return decision(Event.IGNORE, accepted, running, nextMatchingJobSeen, controlRequest);
    }

    private static Decision decision(Event event, boolean accepted, boolean running,
                                     boolean matchingJobSeen, ControlRequest controlRequest) {
        return new Decision(event, accepted, running, matchingJobSeen,
                controlRequest == null ? ControlRequest.NONE : controlRequest);
    }
}
