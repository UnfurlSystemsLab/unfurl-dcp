package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.util.Objects;

/**
 * Codec: the one JSON wire form of {@link RegistrationReport} (DCP HTTP Binding §4), shared by hosts that serve
 * {@code /dcp/registrations} and observers that read it, so the two can never disagree.
 *
 * <p>Writing is deterministic: snake_case, sorted properties, ISO-8601 instants. Reading ignores unknown fields, so a
 * newer host stays readable, but every required field and every record invariant is enforced. A body that fails either
 * is a {@link RegistrationReportException} whose message never repeats body content.
 */
public final class RegistrationReportCodec {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    /** Upper bound on a report body: a host's registrations are small; anything larger is not a report. */
    public static final int MAX_BYTES = 1_048_576;

    /** Utility class: static codec only. */
    private RegistrationReportCodec() { }

    /** Serializes a report. */
    public static byte[] encode(RegistrationReport report) {
        Objects.requireNonNull(report, "report");
        try {
            return JSON.writeValueAsBytes(report);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unable to encode registration report", failure);
        }
    }

    /**
     * Parses a report body.
     *
     * @throws RegistrationReportException when the body is too large, is not JSON, or breaks a field or record rule
     */
    public static RegistrationReport decode(byte[] json) {
        if (json == null || json.length == 0) {
            throw new RegistrationReportException("the registration report body is empty", null);
        }
        if (json.length > MAX_BYTES) {
            throw new RegistrationReportException("the registration report body is larger than " + MAX_BYTES + " bytes", null);
        }
        try {
            RegistrationReport report = JSON.readValue(json, RegistrationReport.class);
            if (report == null) throw new RegistrationReportException("the registration report body is null", null);
            return report;
        } catch (IOException malformed) {
            Throwable rule = malformed.getCause();
            String detail = rule instanceof IllegalArgumentException || rule instanceof NullPointerException
                    ? rule.getMessage() : "not a registration report";
            throw new RegistrationReportException("the registration report is malformed: " + detail, malformed);
        }
    }

    /** Failure: a body that is not a valid registration report. */
    public static final class RegistrationReportException extends IllegalArgumentException {
        /** Constructor: a content-free message and the parse cause. */
        public RegistrationReportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
