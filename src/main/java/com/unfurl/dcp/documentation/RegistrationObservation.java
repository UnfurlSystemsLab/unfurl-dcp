package com.unfurl.dcp.documentation;

import java.time.Instant;
import java.util.Objects;

/**
 * Sum type (sealed): the only ways an observation of one service root's registration report can end (DCP HLD-C2 G.3).
 * The embedding service maps its transport outcomes onto exactly one of these; DCP itself makes no HTTP call.
 */
public sealed interface RegistrationObservation {
    /** When the observation was made. */
    Instant observedAt();

    /**
     * The host answered with a report that decoded.
     *
     * @param report the decoded report; its own {@code observedAt} is the host's clock
     * @param observedAt when the observer received it
     */
    record Reported(RegistrationReport report, Instant observedAt) implements RegistrationObservation {
        /** Validating constructor. */
        public Reported {
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    /**
     * No usable report: a connection failure, a timeout, a {@code 5xx} or other non-success status, or a body that does
     * not decode.
     *
     * @param detail what failed, never the response body
     * @param observedAt when the observation ended
     */
    record Unreachable(String detail, Instant observedAt) implements RegistrationObservation {
        /** Validating constructor. */
        public Unreachable {
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    /**
     * The host refused the observer ({@code 401} or {@code 403}).
     *
     * @param status the HTTP status
     * @param observedAt when the observation ended
     */
    record Unauthorized(int status, Instant observedAt) implements RegistrationObservation {
        /** Validating constructor: only 401 and 403. */
        public Unauthorized {
            if (status != 401 && status != 403) {
                throw new IllegalArgumentException("unauthorized is 401 or 403");
            }
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }
}
