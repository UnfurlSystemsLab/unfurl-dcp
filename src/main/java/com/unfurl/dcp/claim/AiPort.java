package com.unfurl.dcp.claim;

import java.util.List;

/**
 * The AI integration surface (DCP HLD-C2 A.9). {@code required} says the component needs AI capability to operate; it
 * does not make the component an intelligent component (that takes a negotiation surface, A.8).
 *
 * <p>Pattern: immutable <b>value object</b> that keeps presence: every member is a wrapper or a nullable list, so a member
 * the claim did not write stays null and {@code ClaimValidator} can reject a missing required member (HLD-C2 A.9, "A
 * declared port is complete"); nothing is defaulted. A list is frozen when present and stays null when absent.
 *
 * @param required    required: whether the component requires AI capability to operate.
 * @param intents     optional: the AI intents it uses (for example summarize, classify).
 * @param modelAccess optional: how it reaches a model.
 * @param costMetered optional: whether its AI use is metered.
 * @param notes       optional free-text notes.
 */
public record AiPort(Boolean required, List<String> intents, ModelAccess modelAccess, Boolean costMetered,
                     String notes) {
    /** Compact constructor: freezes the intent list when present. */
    public AiPort {
        intents = intents == null ? null : List.copyOf(intents);
    }
}
