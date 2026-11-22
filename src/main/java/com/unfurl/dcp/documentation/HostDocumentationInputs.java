package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.runtimebinding.TargetEnvironment;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Parameter Object: the inputs of a host view (DCP HLD-C2 G.2): a host documenting itself and the components it runs in
 * process, from its own verified state. It needs no composition files.
 *
 * @param ownClaim            the host's packaged claim
 * @param ownArtifacts        the host's own shipped artifacts
 * @param local               the host's current registrations, built from its registrar
 * @param hosted              admission evidence for every component the host admitted in process
 * @param identity            whether the host's composition has an identity contract
 * @param protectedComponents the components that composition's identity contract protects
 * @param policy              the visibility policy
 * @param serviceRoot         the host's own service root
 * @param environment         the host's target environment
 * @param documentId          the projection's id
 * @param generatedBy         the projector's identity
 */
public record HostDocumentationInputs(Claim ownClaim, DocumentationArtifactStore ownArtifacts, RegistrationReport local,
                                      List<HostedComponentDocumentation> hosted, IdentityMode identity,
                                      Set<URI> protectedComponents, VisibilityPolicy policy, URI serviceRoot,
                                      TargetEnvironment environment, URI documentId, String generatedBy) {
    /** Validating constructor: everything is required; collections are copied. */
    public HostDocumentationInputs {
        Objects.requireNonNull(ownClaim, "ownClaim");
        Objects.requireNonNull(ownArtifacts, "ownArtifacts");
        Objects.requireNonNull(local, "local");
        hosted = List.copyOf(Objects.requireNonNull(hosted, "hosted"));
        Objects.requireNonNull(identity, "identity");
        protectedComponents = Set.copyOf(Objects.requireNonNull(protectedComponents, "protectedComponents"));
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(serviceRoot, "serviceRoot");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(generatedBy, "generatedBy");
    }
}
