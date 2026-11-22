package com.unfurl.dcp.testing;

import com.unfurl.dcp.claim.ApiDocument;
import com.unfurl.dcp.claim.ArtifactMediaType;
import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimHash;
import com.unfurl.dcp.claim.ClaimKey;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.OfferDocumentation;
import com.unfurl.dcp.claim.OperationDocumentation;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.ContractFreezer;
import com.unfurl.dcp.documentation.DocumentationArtifactStore;
import com.unfurl.dcp.documentation.DocumentationInputs;
import com.unfurl.dcp.documentation.EntrySurface;
import com.unfurl.dcp.documentation.Registration;
import com.unfurl.dcp.documentation.RegistrationObservation;
import com.unfurl.dcp.documentation.RegistrationReport;
import com.unfurl.dcp.documentation.VisibilityPolicy;
import com.unfurl.dcp.host.ContractSource;
import com.unfurl.dcp.host.VerifiedComposition;
import com.unfurl.dcp.runtimebinding.ConfigRef;
import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.dcp.runtimebinding.DeploymentControls;
import com.unfurl.dcp.runtimebinding.DeploymentKind;
import com.unfurl.dcp.runtimebinding.Lifecycle;
import com.unfurl.dcp.runtimebinding.ProviderInstance;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimePolicy;
import com.unfurl.dcp.runtimebinding.SecretRef;
import com.unfurl.dcp.runtimebinding.TargetEnvironment;
import com.unfurl.dcp.validation.ErrorCode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test Data Builder: a complete, signed deployment for documentation tests. It holds the claims, their shipped files,
 * the child contracts (with real provider pins), the runtime bindings, and helpers for registration reports that match,
 * or deliberately do not match, the pins.
 *
 * <p>The default world has:
 * <ul>
 *   <li>host {@code urn:host@1.0.0}, serving {@code agent.run} over HTTP at {@code http://host:7979};</li>
 *   <li>component {@code urn:tool@0.2.0}, providing {@code tool.lookup} hosted in process by the host;</li>
 *   <li>host {@code urn:flow@1.0.0}, serving {@code workflow.execute} at {@code http://flow:8080};</li>
 *   <li>optionally, an identity contract whose provider is external.</li>
 * </ul>
 */
public final class DocumentationWorld {
    private static final KeyPair KEYS = CryptoFixtures.keyPair();
    public static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");
    public static final URI HOST = URI.create("urn:host");
    public static final URI TOOL = URI.create("urn:tool");
    public static final URI FLOW = URI.create("urn:flow");
    public static final URI IDP = URI.create("urn:idp");
    public static final URI HOST_ROOT = URI.create("http://host:7979");
    public static final URI FLOW_ROOT = URI.create("http://flow:8080");

    public final Map<String, byte[]> files = new LinkedHashMap<>();
    public final Map<ClaimKey, Claim> claims = new LinkedHashMap<>();
    public final List<CompositionContract> children = new ArrayList<>();
    public final List<RuntimeBinding> bindings = new ArrayList<>();
    public VisibilityPolicy policy = VisibilityPolicy.uniform(com.unfurl.dcp.documentation.Visibility.INTERNAL);

    /** The default world, without identity. */
    public static DocumentationWorld standard() {
        DocumentationWorld world = new DocumentationWorld();
        Claim host = world.claim("urn:host", "1.0.0", "Host", "agent.run");
        Claim tool = world.claim("urn:tool", "0.2.0", "Tool", "tool.lookup");
        Claim flow = world.claim("urn:flow", "1.0.0", "Flow", "workflow.execute");
        world.child("urn:c:agent", "agent.run", host, "urn:client", "1.0.0", Map.of());
        world.httpBinding("urn:c:agent", URI.create("http://host:7979/dcp/agent.run"));
        world.child("urn:c:tool", "tool.lookup", tool, "urn:host", "1.0.0", Map.of());
        world.binding("urn:c:tool", DeploymentKind.IN_PROCESS, null, true);
        world.child("urn:c:flow", "workflow.execute", flow, "urn:client", "1.0.0", Map.of());
        world.httpBinding("urn:c:flow", URI.create("http://flow:8080/dcp/workflow.execute"));
        return world;
    }

    /** Adds an identity contract protecting {@code urn:host} and {@code urn:flow}, and its complete binding. */
    public DocumentationWorld withIdentity() {
        Claim idp = claim("urn:idp", "1.0.0", "Identity", "identity-context");
        child("urn:c:identity", "identity-context", idp, "urn:host", "1.0.0", Map.of("neededBy", List.of("urn:host", "urn:flow")));
        Map<String, ConfigRef> refs = Map.of("token_endpoint", new ConfigRef("config://acme/identity/token"),
                "verification_keys", new ConfigRef("config://acme/identity/keys"));
        Map<String, Object> values = Map.of("issuer", "https://idp.example.test", "audiences", Map.of("urn:host", "host", "urn:flow", "flow"),
                "claims", Map.of("tenant", "tenant", "roles", "roles", "permissions", "permissions"));
        bindings.add(new RuntimeBinding(URI.create("urn:b:identity"), URI.create("urn:c:identity"), "1.0.0", env(),
                new ProviderInstance(IDP, "1.0.0", "idp", DeploymentKind.EXTERNAL_SAAS, null, null, new SecretRef("secret://acme/identity/host")),
                null, new RuntimePolicy(true, null, "identity", true), new Configuration(values, refs, Map.of()),
                new DeploymentControls(Map.of()), new Lifecycle(true), null));
        return this;
    }

    /** A documented claim offering one capability with one operation of the same name, plus its shipped files. */
    public Claim claim(String uri, String version, String name, String capability) {
        return claim(uri, version, name, capability, List.of(capability));
    }

    /** A documented claim offering one capability with the given operations. */
    public Claim claim(String uri, String version, String name, String capability, List<String> operations) {
        List<OperationDocumentation> docs = new ArrayList<>();
        for (String operation : operations) {
            String request = "{\"type\":\"object\",\"properties\":{\"" + operation + "\":{\"$ref\":\"#/$defs/Value\"}},"
                    + "\"$defs\":{\"Value\":{\"type\":\"string\"}}}";
            String response = "{\"type\":\"object\",\"required\":[\"result\"],\"properties\":{\"result\":{\"type\":\"string\"}}}";
            docs.add(new OperationDocumentation(operation, "Run " + operation,
                    file("dcp/schemas/" + capability + "." + operation + ".request.schema.json", request, ArtifactMediaType.SCHEMA_JSON),
                    file("dcp/schemas/" + capability + "." + operation + ".response.schema.json", response, ArtifactMediaType.SCHEMA_JSON)));
        }
        Offer base = Fixtures.validProviderClaim(capability).offers().get(0);
        Offer offer = new Offer(capability, name + " " + capability, base.consumerAccess(), base.offerInterface(), base.stability(),
                base.version(), false, null, new OfferDocumentation(name + " capability", docs));
        Claim claim = DocumentationFixtures.withIdentity(DocumentationFixtures.withOffers(Fixtures.validProviderClaim(capability), List.of(offer)),
                uri, version);
        Claim named = new Claim(new com.unfurl.dcp.claim.Identity(claim.identity().uri(), name, claim.identity().kind(), version,
                claim.identity().publisher(), claim.identity().publisherUri()), claim.domain(), claim.refusals(), claim.dependencies(),
                claim.offers(), claim.conflictResolution(), claim.negotiationSurface(), claim.integrationPorts(),
                faultsFor(claim, capability), claim.metadata(), claim.apiDocuments());
        put(named);
        return named;
    }

    /** Replaces a claim (same identity) in the world and re-pins every child that names it. */
    public void put(Claim claim) {
        ClaimKey key = ClaimKey.of(claim);
        claims.put(key, claim);
        for (int i = 0; i < children.size(); i++) {
            CompositionContract child = children.get(i);
            if (child.parties().provider().claimUri().equals(key.uri()) && child.parties().provider().claimVersion().equals(key.version())) {
                Map<String, Object> extensions = new LinkedHashMap<>(child.metadata().extensions());
                extensions.put("claimHash", ClaimHash.canonical(claim));
                children.set(i, withExtensions(child, extensions));
            }
        }
    }

    /** Adds an API document to a claim, with its shipped file. */
    public Claim withApi(Claim claim, String apiId, String yaml, List<String> surfaces) {
        ArtifactRef ref = file("dcp/openapi/" + apiId + ".openapi.yaml", yaml, ArtifactMediaType.OPENAPI_YAML);
        List<ApiDocument> docs = new ArrayList<>(claim.apiDocuments());
        docs.add(new ApiDocument(apiId, "Product routes " + apiId, ref, surfaces));
        Claim updated = DocumentationFixtures.withApiDocuments(claim, docs);
        put(updated);
        return updated;
    }

    /** Ships a file and returns its pinned reference. */
    public ArtifactRef file(String path, String content, ArtifactMediaType type) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        files.put(DocumentationFixtures.sha256(bytes), bytes);
        return new ArtifactRef(path, type, DocumentationFixtures.sha256(bytes));
    }

    /** Adds a child contract pinning {@code provider}'s claim. */
    public CompositionContract child(String id, String capability, Claim provider, String consumer, String consumerVersion,
                                     Map<String, Object> extra) {
        Map<String, Object> extensions = new LinkedHashMap<>(extra);
        extensions.put("artifactCoordinates", "example:" + provider.identity().name().toLowerCase() + ":" + provider.identity().version());
        extensions.put("artifactSha256", "a".repeat(64));
        extensions.put("claimHash", ClaimHash.canonical(provider));
        CompositionContract contract = DocumentationFixtures.contract(id, capability, provider.identity().uri().toString(),
                provider.identity().version(), consumer, consumerVersion, extensions);
        children.add(contract);
        return contract;
    }

    /** Adds an enabled HTTP binding at the given endpoint. */
    public void httpBinding(String contractId, URI endpoint) {
        binding(contractId, DeploymentKind.CONTAINER, endpoint, true);
    }

    /** Adds a binding for a contract. */
    public void binding(String contractId, DeploymentKind kind, URI endpoint, boolean enabled) {
        CompositionContract contract = children.stream().filter(c -> c.contractId().toString().equals(contractId)).findFirst().orElseThrow();
        bindings.add(new RuntimeBinding(URI.create(contractId.replace(":c:", ":b:")), contract.contractId(), contract.contractVersion(), env(),
                new ProviderInstance(contract.parties().provider().claimUri(), contract.parties().provider().claimVersion(),
                        "instance", kind, endpoint, null, null),
                null, new RuntimePolicy(enabled, 2000, "test", true), new Configuration(Map.of()),
                new DeploymentControls(Map.of()), new Lifecycle(true), null));
    }

    /** Replaces one binding. */
    public void replaceBinding(String contractId, java.util.function.UnaryOperator<RuntimeBinding> change) {
        for (int i = 0; i < bindings.size(); i++) {
            if (bindings.get(i).contractId().toString().equals(contractId)) bindings.set(i, change.apply(bindings.get(i)));
        }
    }

    /** The shared target environment. */
    public static TargetEnvironment env() {
        return new TargetEnvironment("test", "acme", null, null);
    }

    /** Signs and verifies the composition. */
    public VerifiedComposition composition() {
        ContractFreezer freezer = new ContractFreezer(CryptoFixtures.signingKeyRef());
        List<Map<String, String>> contains = children.stream().map(c -> Map.of("contractId", c.contractId().toString())).toList();
        CompositionContract root = DocumentationFixtures.contract("urn:c:root", "assembly.aggregate", "urn:assembly", "1.0.0",
                "urn:assembly", "1.0.0", Map.of("contains", contains));
        List<ContractSource> sources = children.stream().map(c -> new ContractSource(c.contractId() + ".json",
                freezer.freeze(c, CryptoFixtures.signer(KEYS)).canonicalBytes())).toList();
        return VerifiedComposition.load(new ContractSource("root.frozen.json", freezer.freeze(root, CryptoFixtures.signer(KEYS)).canonicalBytes()),
                sources, CryptoFixtures.keySet(KEYS));
    }

    /** A store over the shipped files, verifying hashes like the real stores. */
    public DocumentationArtifactStore store() {
        return ref -> {
            byte[] bytes = files.get(ref.sha256());
            if (bytes == null) {
                throw new DocumentationArtifactStore.ArtifactUnavailableException(ErrorCode.ARTIFACT_UNAVAILABLE, ref.path() + " missing");
            }
            if (!DocumentationFixtures.sha256(bytes).equals(ref.sha256())) {
                throw new DocumentationArtifactStore.ArtifactUnavailableException(ErrorCode.ARTIFACT_HASH_MISMATCH, ref.path() + " differs");
            }
            return bytes.clone();
        };
    }

    /** The projection inputs. */
    public DocumentationInputs inputs() {
        return new DocumentationInputs(composition(), claims, bindings, store(), policy, null, URI.create("urn:docs:test"), "test-projector@1");
    }

    /** As {@link #report(URI, String...)}, with the product surfaces the host reports as served. */
    public RegistrationReport report(URI host, List<String> servedSurfaces, String... contractIds) {
        RegistrationReport base = report(host, contractIds);
        return new RegistrationReport(base.componentUri(), base.componentVersion(), base.claimHash(), base.observedAt(),
                base.registrations(), servedSurfaces);
    }

    /** A report from a host listing the given contracts as its own HTTP registrations or hosted ones. */
    public RegistrationReport report(URI host, String... contractIds) {
        Claim hostClaim = claims.get(new ClaimKey(host, version(host)));
        List<Registration> registrations = new ArrayList<>();
        for (String id : contractIds) {
            CompositionContract contract = children.stream().filter(c -> c.contractId().toString().equals(id)).findFirst().orElseThrow();
            URI provider = contract.parties().provider().claimUri();
            Claim providerClaim = claims.get(new ClaimKey(provider, contract.parties().provider().claimVersion()));
            boolean hosted = !provider.equals(host);
            String capability = contract.binding().providerCapability();
            registrations.add(new Registration(contract.contractId(), contract.contractVersion(), capability, provider,
                    providerClaim.identity().version(), ClaimHash.canonical(providerClaim),
                    hosted ? EntrySurface.HOSTED_IN_PROCESS : EntrySurface.HTTP, hosted ? null : "/dcp/" + capability));
        }
        return new RegistrationReport(host, hostClaim.identity().version(), ClaimHash.canonical(hostClaim), T0, registrations, List.of());
    }

    /** The observations of a fully healthy world. */
    public Map<URI, RegistrationObservation> healthy() {
        Map<URI, RegistrationObservation> observations = new LinkedHashMap<>();
        observations.put(HOST_ROOT, new RegistrationObservation.Reported(report(HOST, "urn:c:agent", "urn:c:tool"), T0));
        observations.put(FLOW_ROOT, new RegistrationObservation.Reported(report(FLOW, "urn:c:flow"), T0));
        return observations;
    }

    /** The version a world component is declared with. */
    private String version(URI component) {
        return claims.keySet().stream().filter(k -> k.uri().equals(component)).findFirst().orElseThrow().version();
    }

    /** The fixture fault policy, retargeted at the given capability. */
    private static com.unfurl.dcp.fault.FaultPolicy faultsFor(Claim claim, String capability) {
        return new com.unfurl.dcp.fault.FaultPolicy(claim.faults().emitted().stream().map(fault -> new com.unfurl.dcp.fault.FaultDeclaration(
                fault.code(), fault.category(), fault.severity(), fault.description(),
                new com.unfurl.dcp.fault.FaultAffects(List.of(), List.of(capability), List.of()), fault.evidence(), fault.propagation(),
                fault.remediation())).toList());
    }

    /** A contract with replaced extensions. */
    private static CompositionContract withExtensions(CompositionContract c, Map<String, Object> extensions) {
        return new CompositionContract(c.contractId(), c.contractVersion(), c.parties(), c.binding(), c.dataMapping(), c.transport(),
                c.expectations(), c.provenance(), c.trust(), c.invalidation(), new com.unfurl.dcp.contract.CompositionContractMetadata(extensions));
    }
}
