package com.unfurl.dcp.documentation;

/**
 * Enumeration: whether the verified composition contains an identity contract (DCP HLD-C2 G.1 input 6). It decides the
 * rendered security schemes and is never read from configuration.
 */
public enum IdentityMode {
    /** The composition has an identity-context contract: protected components render bearer security. */
    IN_CONTRACT,
    /** No identity contract: nothing renders security, and the output states the trusted-network mode. */
    ABSENT
}
