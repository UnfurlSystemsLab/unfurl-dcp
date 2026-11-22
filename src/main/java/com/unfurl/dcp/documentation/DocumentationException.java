package com.unfurl.dcp.documentation;

/**
 * Failure (base): the documentation projection cannot produce documentation, with a stable machine code. Expected
 * per-entry outcomes are never exceptions: they are report reasons. These are refusals of the whole projection, because
 * its static inputs are inconsistent or incomplete.
 */
public class DocumentationException extends IllegalStateException {
    private final String code;

    /** Constructor: the code prefixes the message so logs carry it. */
    public DocumentationException(String code, String message) {
        this(code, message, null);
    }

    /** Constructor with a cause. */
    public DocumentationException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }

    /** The stable failure code. */
    public String code() {
        return code;
    }

    /** Input refusal codes (DCP LLD, <em>Capability Documentation Projection</em>). */
    public static final String CLAIM_MISSING = "CLAIM_MISSING";
    public static final String CLAIM_PIN_MISSING = "CLAIM_PIN_MISSING";
    public static final String CLAIM_PIN_MISMATCH = "CLAIM_PIN_MISMATCH";
    public static final String CLAIM_OFFER_MISSING = "CLAIM_OFFER_MISSING";
    public static final String ARTIFACT_INVALID = "ARTIFACT_INVALID";
    public static final String SERVICE_ROOT_CONFLICT = "SERVICE_ROOT_CONFLICT";
    public static final String TARGET_ENVIRONMENT_INCONSISTENT = "TARGET_ENVIRONMENT_INCONSISTENT";
    public static final String BINDING_DUPLICATE = "BINDING_DUPLICATE";
    public static final String HOST_VIEW_INPUT_MISMATCH = "HOST_VIEW_INPUT_MISMATCH";
    public static final String IDENTITY_BINDING_INCOMPLETE = "IDENTITY_BINDING_INCOMPLETE";
    public static final String DOCUMENTATION_GAP = "DOCUMENTATION_GAP";
    /** Rendering refusal codes. */
    public static final String API_DOCUMENT_PATH_CONFLICT = "API_DOCUMENT_PATH_CONFLICT";
    public static final String SCHEMA_NAME_COLLISION = "SCHEMA_NAME_COLLISION";
    public static final String RENDER_REFERENCE_UNRESOLVED = "RENDER_REFERENCE_UNRESOLVED";
    public static final String UNSUPPORTED_SURFACE = "UNSUPPORTED_SURFACE";
}
