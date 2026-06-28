package com.unfurl.dcp.broker;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.spi.CapabilityRegistrar;
import com.unfurl.dcp.spi.ContractInvocableFactory;
import com.unfurl.substrate.policy.ExecutionContext;

/**
 * Ports & Adapters port: defines the deterministic runtime broker boundary that hosts use to
 * present provider claims, accept fabric-frozen contracts, and revoke dynamic capability exposure.
 * Inputs are already-authenticated host context plus DCP model records; outputs are structured
 * dispositions/handles so no model reasoning or network lookup leaks into the runtime path.
 */
public interface CompositionBroker {
    /**
     * Strategy entry point: validates a provider claim and maps it to an accepted or refused
     * disposition using only the injected frozen-contract store and offline verifier.
     */
    Disposition present(Claim claim, ExecutionContext context);

    /**
     * Adapter handoff: re-fetches and re-verifies an accepted contract before registering its
     * single binding through the mutable host registrar; invalid or stale dispositions fail before
     * any capability is exposed.
     */
    RegistrationHandle accept(
            Disposition disposition,
            CapabilityRegistrar registrar,
            ContractInvocableFactory invocableFactory,
            ExecutionContext context
    );

    /**
     * Lifecycle reversal: unregisters every capability named by the handle and treats missing
     * handles as idempotent no-ops so hosts can safely clean up dynamic components.
     */
    void revoke(RegistrationHandle handle, CapabilityRegistrar registrar, ExecutionContext context);

    /**
     * Runtime invalidation hook: emits the invalidation event and revokes capabilities without
     * renegotiating, preserving the design-time/runtime firewall.
     */
    void invalidate(RegistrationHandle handle, CapabilityRegistrar registrar, ExecutionContext context);
}
