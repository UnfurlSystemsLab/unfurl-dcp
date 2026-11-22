package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.TransportKind;
import com.unfurl.dcp.fault.FaultDeclaration;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;

import java.util.List;
import java.util.Optional;

/**
 * Utility: the small rules every documentation projector applies to one entry, kept in one place so the full projection,
 * the host view and the gap gate cannot disagree.
 */
final class Entries {
    private Entries() { }

    /**
     * The surface of an entry (DCP HLD-C2 G.1): {@code EXTERNAL} when its provider offer is {@code external_protocol},
     * whatever the deployment kind, because the provider is then reached over its own protocol whether it is deployed
     * with the composition or run by the customer; otherwise from its binding's deployment kind; without a binding, from
     * the contract's transport, used only to label a {@code BINDING_MISSING} entry.
     *
     * @param offer the provider offer the contract binds, or null when the provider claim is not available (the gap gate
     *              then reports the entry as a gap if it must be documented)
     */
    static EntrySurface surfaceOf(CompositionContract contract, RuntimeBinding binding, Offer offer) {
        if (offer != null && offer.offerInterface() != null
                && offer.offerInterface().interfaceKind() == InterfaceKind.EXTERNAL_PROTOCOL) {
            return EntrySurface.EXTERNAL;
        }
        if (binding != null && binding.providerInstance() != null && binding.providerInstance().deploymentKind() != null) {
            return EntrySurface.of(binding.providerInstance().deploymentKind());
        }
        TransportKind transport = contract.transport() == null ? null : contract.transport().kind();
        return transport == TransportKind.HTTP_JSON || transport == TransportKind.GRPC ? EntrySurface.HTTP : EntrySurface.HOSTED_IN_PROCESS;
    }

    /** The offer of a claim for a capability, when the claim makes it. */
    static Optional<Offer> offer(Claim claim, String capability) {
        return claim.offers().stream().filter(offer -> capability.equals(offer.capability())).findFirst();
    }

    /** Whether an offer has documentable payloads: documentation with at least one operation. */
    static boolean documented(Offer offer) {
        return offer.documentation() != null && !offer.documentation().operations().isEmpty();
    }

    /** Whether an entry with this surface and visibility must be documented, which makes an undocumented offer a gap. */
    static boolean mustBeDocumented(EntrySurface surface, Visibility visibility) {
        return surface != EntrySurface.EXTERNAL && visibility != Visibility.PRIVATE;
    }

    /** The fault codes a claim declares for a capability (DCP HLD-C2 A.10), sorted. */
    static List<String> faultsOf(Claim claim, String capability) {
        if (claim.faults() == null) return List.of();
        return claim.faults().emitted().stream()
                .filter(fault -> fault != null && fault.affects() != null && fault.affects().offers().contains(capability))
                .map(FaultDeclaration::code).sorted().distinct().toList();
    }
}
