package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.host.VerifiedComposition;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.substrate.ports.host.ConfigResolver;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parameter Object: the static inputs of a documentation projection (DCP HLD-C2 G.1 inputs 1-4 and 7). The runtime
 * input, one registration observation per service root, is given later to {@link DocumentationPlan#project}.
 *
 * @param composition    the verified composition; an unverified set cannot be expressed here
 * @param providerClaims the bundled claims by identity ({@code BundledClaims})
 * @param runtimeBindings the children's runtime bindings
 * @param artifacts      the bundle's content-addressed artifact store
 * @param visibility     the product's visibility policy
 * @param configResolver resolves {@code base_url_ref}; null when the deployment mounts no configuration
 * @param documentId     the projection's id
 * @param generatedBy    the projector's identity
 */
public record DocumentationInputs(VerifiedComposition composition, Map<ClaimKey, Claim> providerClaims,
                                  List<RuntimeBinding> runtimeBindings, DocumentationArtifactStore artifacts,
                                  VisibilityPolicy visibility, ConfigResolver configResolver, URI documentId,
                                  String generatedBy) {
    /** Validating constructor: everything but the config resolver is required; collections are copied. */
    public DocumentationInputs {
        Objects.requireNonNull(composition, "composition");
        providerClaims = Map.copyOf(Objects.requireNonNull(providerClaims, "providerClaims"));
        runtimeBindings = List.copyOf(Objects.requireNonNull(runtimeBindings, "runtimeBindings"));
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(generatedBy, "generatedBy");
    }
}
