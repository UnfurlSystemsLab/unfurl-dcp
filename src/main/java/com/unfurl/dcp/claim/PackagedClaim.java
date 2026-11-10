package com.unfurl.dcp.claim;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/**
 * Repository (read-only): reads a component's claim from the {@code META-INF/unfurl-catalog.yaml} resource packaged in
 * its artifact (DCP HLD-C §3.10). This is the one way a runtime obtains its own claim, so no runtime builds a second,
 * profile-dependent claim of its own.
 *
 * <p>Only the top-level {@code claim} block is bound; the manifest's {@code catalog} block belongs to the catalog that
 * admits the artifact and is ignored here. Every claim returned has passed {@link ClaimValidator} with no errors, and
 * the reader never edits it. The hash used for pinning is {@link ClaimHash#canonical}.
 */
public final class PackagedClaim {
    /** Classpath and archive path of the catalog manifest that carries a component's claim. */
    public static final String RESOURCE = "META-INF/unfurl-catalog.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PackagedClaim() {
    }

    /**
     * Loads the packaged claim whose identity is {@code expectedUri} from the manifests visible to {@code loader}.
     *
     * <p>A runtime's classpath usually holds other components' jars too, each with its own manifest, so the claim is
     * selected by identity rather than by resource order. Exactly one manifest must carry it.
     *
     * @param loader      the class loader to read {@link #RESOURCE} from; must not be null.
     * @param expectedUri the identity URI of the component whose claim is wanted; must not be null.
     * @return the validated claim.
     * @throws PackagedClaimException when no manifest, or more than one, carries a claim with that identity, or when a
     *                                manifest cannot be read, has no claim block, or holds an invalid claim.
     */
    public static Claim load(ClassLoader loader, URI expectedUri) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(expectedUri, "expectedUri");
        List<Claim> matches = new ArrayList<>();
        Enumeration<URL> resources;
        try {
            resources = loader.getResources(RESOURCE);
        } catch (IOException ex) {
            throw new PackagedClaimException("unable to list " + RESOURCE + ": " + ex.getMessage(), List.of());
        }
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            JsonNode claimNode = claimNode(read(resource), resource.toString());
            // Other components' manifests are expected on the classpath; only the identity match is parsed and checked.
            if (expectedUri.toString().equals(claimNode.path("identity").path("uri").asText())) {
                matches.add(bind(claimNode, resource.toString()));
            }
        }
        if (matches.isEmpty()) {
            throw new PackagedClaimException("no " + RESOURCE + " on the classpath carries the claim " + expectedUri, List.of());
        }
        if (matches.size() > 1) {
            throw new PackagedClaimException(matches.size() + " copies of " + RESOURCE + " carry the claim " + expectedUri
                    + "; exactly one artifact may carry a component's claim", List.of());
        }
        return matches.get(0);
    }

    /**
     * Reads the claim block of catalog manifest bytes, such as the {@link #RESOURCE} entry read from an artifact.
     *
     * @param manifestBytes the manifest bytes; must not be null.
     * @return the validated claim.
     * @throws PackagedClaimException when the bytes are not YAML, have no {@code claim} block, or hold an invalid claim.
     */
    public static Claim parse(byte[] manifestBytes) {
        Objects.requireNonNull(manifestBytes, "manifestBytes");
        return bind(claimNode(manifestBytes, RESOURCE), RESOURCE);
    }

    /**
     * Reads a manifest resource fully.
     *
     * @param resource the resource URL.
     * @return its bytes.
     * @throws PackagedClaimException when it cannot be read.
     */
    private static byte[] read(URL resource) {
        try (InputStream stream = resource.openStream()) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new PackagedClaimException("unable to read " + resource + ": " + ex.getMessage(), List.of());
        }
    }

    /**
     * Selects the top-level {@code claim} block of a manifest.
     *
     * @param manifestBytes the manifest bytes.
     * @param source        where the bytes came from, for messages.
     * @return the claim block.
     * @throws PackagedClaimException when the bytes are not YAML or have no claim block.
     */
    private static JsonNode claimNode(byte[] manifestBytes, String source) {
        try {
            JsonNode claim = YAML.readTree(manifestBytes).path("claim");
            if (!claim.isObject()) {
                throw new PackagedClaimException(source + " has no claim block", List.of());
            }
            return claim;
        } catch (IOException ex) {
            throw new PackagedClaimException("unable to parse " + source + ": " + ex.getMessage(), List.of());
        }
    }

    /**
     * Binds a claim block to {@link Claim} and validates it.
     *
     * @param claimNode the claim block.
     * @param source    where the block came from, for messages.
     * @return the claim, which has no validation errors.
     * @throws PackagedClaimException when the block does not bind or the claim has validation errors.
     */
    private static Claim bind(JsonNode claimNode, String source) {
        Claim claim;
        try {
            claim = YAML.treeToValue(claimNode, Claim.class);
        } catch (IOException | IllegalArgumentException ex) {
            throw new PackagedClaimException("the claim in " + source + " is malformed: " + ex.getMessage(), List.of());
        }
        SchemaValidationReport report = new ClaimValidator().validate(claim);
        if (!report.valid()) {
            throw new PackagedClaimException("the claim in " + source + " is invalid", report.diagnostics());
        }
        return claim;
    }

    /**
     * Failure to obtain a packaged claim. A runtime treats it as fatal: it has no claim it may report or check.
     */
    public static final class PackagedClaimException extends RuntimeException {
        private final List<Diagnostic> diagnostics;

        /**
         * @param message     what went wrong, naming the manifest.
         * @param diagnostics the claim-validation diagnostics, empty when the failure came before validation.
         */
        PackagedClaimException(String message, List<Diagnostic> diagnostics) {
            super(diagnostics.isEmpty() ? message : message + ": " + diagnostics);
            this.diagnostics = List.copyOf(diagnostics);
        }

        /**
         * @return the claim-validation diagnostics, empty when the failure came before validation.
         */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
