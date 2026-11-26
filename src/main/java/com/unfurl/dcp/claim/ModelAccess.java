package com.unfurl.dcp.claim;

/** How a component reaches a model (DCP HLD-C2 A.9 {@code ai.model_access}). */
public enum ModelAccess {
    NONE,
    EXTERNAL_PROVIDER,
    EMBEDDED_MODEL,
    HOST_PROVIDED
}
