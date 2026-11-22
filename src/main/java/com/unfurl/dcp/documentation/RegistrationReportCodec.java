package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
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
 * <p>Writing is deterministic: snake_case, sorted properties, ISO-8601 instants, lower-case enumerations, and absent
 * fields omitted rather than written as {@code null} (a hosted registration has no {@code path}). Reading ignores unknown fields, so a
 * newer host stays readable, but every required field and every record invariant is enforced. A body that fails either
 * is a {@link RegistrationReportException} whose message never repeats body content: it states a broken rule
 * ({@link WireRuleViolation}), the name of a missing required field, or the location of an unreadable field
 * ({@code registrations[0].surface}), and nothing else. It carries no parse cause, because the parser's own messages
 * quote the input, and a decode error becomes an observation detail that projectors report and log.
 */
public final class RegistrationReportCodec {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
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
            throw new RegistrationReportException("the registration report is malformed: " + detail(malformed), null);
        }
    }

    /**
     * The content-free detail of a parse failure. Only messages known to be fixed text are forwarded: a
     * {@link WireRuleViolation}'s rule, and the field name a record's {@code requireNonNull} reports for a missing
     * required field. Any other failure (an unparsable URI or timestamp, a wrong JSON type, a syntax error) is reported
     * by its field location alone, built from property names and array indexes, never from values.
     */
    private static String detail(IOException malformed) {
        Throwable rule = malformed.getCause();
        if (rule instanceof WireRuleViolation || rule instanceof NullPointerException) {
            return rule.getMessage();
        }
        if (malformed instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            StringBuilder location = new StringBuilder();
            for (JsonMappingException.Reference reference : mapping.getPath()) {
                if (reference.getFieldName() != null) {
                    if (location.length() > 0) location.append('.');
                    location.append(reference.getFieldName());
                } else if (reference.getIndex() >= 0) {
                    location.append('[').append(reference.getIndex()).append(']');
                }
            }
            if (location.length() > 0) return "unreadable value at " + location;
        }
        return "not a registration report";
    }

    /** Failure: a body that is not a valid registration report. */
    public static final class RegistrationReportException extends IllegalArgumentException {
        /** Constructor: a content-free message; the decoder passes no cause, because parser messages quote input. */
        public RegistrationReportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
