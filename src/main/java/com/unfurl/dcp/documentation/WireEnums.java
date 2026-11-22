package com.unfurl.dcp.documentation;

import java.util.Arrays;
import java.util.Locale;

/**
 * Codec helper: the one rule for writing the documentation package's enumerations on the wire (DCP HLD-C2 G.2). An
 * enumeration is written as its constant name in lower case ({@code HOSTED_IN_PROCESS} becomes
 * {@code hosted_in_process}), which is exactly the value the DCP schemas list, and read back only in that form.
 *
 * <p>The enumerations {@link EntrySurface}, {@link EntryStatus}, {@link Visibility}, {@link IdentityMode} and
 * {@link SurfaceKind} delegate their {@code @JsonValue} and {@code @JsonCreator} methods here, so the registration
 * report and the projection model can never disagree with each other or with their schemas. {@link ReasonCode} does
 * not use it: G.4 lists reason codes as upper-case identifiers.
 */
final class WireEnums {
    /** Utility class: static helpers only. */
    private WireEnums() { }

    /**
     * The wire value of a constant: its name in lower case.
     *
     * @param constant the constant; never null
     */
    static String wire(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Factory: the constant whose wire value is {@code value}. Matching is exact, so an upper-case Java constant name
     * is refused rather than silently accepted, and the schema stays the only accepted spelling.
     *
     * @param type  the enumeration
     * @param value the wire value read from a document
     * @throws WireRuleViolation for any other value, which the codecs report as a malformed document. The message
     *                            names the enumeration only: the rejected value is untrusted input and is never quoted
     */
    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> wire(constant).equals(value))
                .findFirst()
                .orElseThrow(() -> new WireRuleViolation("unsupported " + type.getSimpleName() + " value"));
    }
}
