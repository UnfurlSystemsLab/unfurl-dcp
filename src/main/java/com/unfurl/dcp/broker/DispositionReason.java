package com.unfurl.dcp.broker;

/**
 * Error-code enum: gives broker accept/refuse/invalidation outcomes stable machine-readable reasons.
 * Hosts use these values for audit and UI explanation without parsing human rationale strings.
 */
public enum DispositionReason {
    MATCH_FOUND,
    NO_MATCHING_CONTRACT,
    SIGNATURE_INVALID,
    CLAIM_MALFORMED,
    DCP_VERSION_UNSUPPORTED,
    BROKER_ACCEPT_INVALID,
    CONTRACT_NOT_FOUND,
    CONTRACT_INVALIDATED
}
