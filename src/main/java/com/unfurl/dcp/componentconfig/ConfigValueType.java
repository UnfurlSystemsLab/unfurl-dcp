package com.unfurl.dcp.componentconfig;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Strategy: the scalar value types a component configuration key may have (LLD, <em>Component configuration</em>, type
 * table). Each type turns a raw YAML value into its effective Java value, or rejects it with the rule it broke. The
 * effective values are JDK types only, so they can cross between a host and a component without sharing a library.
 */
public enum ConfigValueType {
    /** A YAML string; effective type {@link String}. */
    STRING("string") {
        @Override Object parse(Object raw) throws ValueRejection {
            if (raw instanceof String text) return text;
            throw new ValueRejection("must be a string");
        }
    },
    /** An integral number within 64 bits, never a fraction or a string; effective type {@link Long}. */
    INTEGER("integer") {
        @Override Object parse(Object raw) throws ValueRejection {
            if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
                return ((Number) raw).longValue();
            }
            if (raw instanceof BigInteger big && big.bitLength() < Long.SIZE) return big.longValue();
            throw new ValueRejection("must be an integer within 64 bits");
        }
    },
    /** {@code true} or {@code false}; effective type {@link Boolean}. */
    BOOLEAN("boolean") {
        @Override Object parse(Object raw) throws ValueRejection {
            if (raw instanceof Boolean flag) return flag;
            throw new ValueRejection("must be true or false");
        }
    },
    /** An absolute {@code https} URI with a host and no user info, query or fragment; effective type {@link URI}. */
    HTTPS_URI("https-uri") {
        @Override Object parse(Object raw) throws ValueRejection {
            String rule = "must be an absolute https URI with a host and no user info, query or fragment";
            if (!(raw instanceof String text)) throw new ValueRejection(rule);
            try {
                URI uri = new URI(text);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null) {
                    throw new ValueRejection(rule);
                }
                return uri;
            } catch (URISyntaxException malformed) {
                throw new ValueRejection(rule);
            }
        }
    },
    /** A lower-case DNS host name of at most 253 characters; effective type {@link String}. */
    HOST("host") {
        @Override Object parse(Object raw) throws ValueRejection {
            if (raw instanceof String text && text.length() <= 253 && HOST_NAME.matcher(text).matches()) return text;
            throw new ValueRejection("must be a lower-case DNS host name");
        }
    },
    /** An upper-case ISO 4217 currency code the JDK knows; effective type {@link String}. */
    ISO_CURRENCY("iso-currency") {
        @Override Object parse(Object raw) throws ValueRejection {
            String rule = "must be an upper-case ISO 4217 currency code";
            if (!(raw instanceof String text) || !CURRENCY_CODE.matcher(text).matches()) throw new ValueRejection(rule);
            try {
                Currency.getInstance(text);
                return text;
            } catch (IllegalArgumentException unknown) {
                throw new ValueRejection(rule);
            }
        }
    },
    /** A non-negative ISO-8601 duration string such as {@code PT30S}; effective type {@link Duration}. */
    DURATION("duration") {
        @Override Object parse(Object raw) throws ValueRejection {
            String rule = "must be a non-negative ISO-8601 duration such as PT30S";
            if (!(raw instanceof String text)) throw new ValueRejection(rule);
            try {
                Duration duration = Duration.parse(text);
                if (duration.isNegative()) throw new ValueRejection(rule);
                return duration;
            } catch (DateTimeParseException malformed) {
                throw new ValueRejection(rule);
            }
        }
    },
    /** {@code secret://} followed by a non-empty reference without whitespace, passed through unresolved; effective type {@link String}. */
    SECRET_REF("secret-ref") {
        @Override Object parse(Object raw) throws ValueRejection {
            if (raw instanceof String text && SECRET_REFERENCE.matcher(text).matches()) return text;
            throw new ValueRejection("must be a secret:// reference, never an inline secret", true);
        }
    };

    private static final Pattern HOST_NAME =
            Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");
    private static final Pattern SECRET_REFERENCE = Pattern.compile("secret://\\S+");

    private final String wireName;

    ConfigValueType(String wireName) {
        this.wireName = wireName;
    }

    /** Returns the name used in the packaged file, for example {@code https-uri}. */
    public String wireName() {
        return wireName;
    }

    /** Returns whether values of this type have an order, so {@code min}, {@code max} and {@code stricter} apply. */
    public boolean ordered() {
        return this == INTEGER || this == DURATION;
    }

    /** Looks a type up by its packaged-file name. */
    public static Optional<ConfigValueType> ofWireName(String name) {
        for (ConfigValueType type : values()) {
            if (type.wireName.equals(name)) return Optional.of(type);
        }
        return Optional.empty();
    }

    /**
     * Converts one raw YAML value to this type's effective Java value.
     *
     * @throws ValueRejection naming the rule, never the value, when the raw value is not of this type
     */
    abstract Object parse(Object raw) throws ValueRejection;
}
