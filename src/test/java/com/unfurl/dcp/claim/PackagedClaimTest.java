package com.unfurl.dcp.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.testing.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests the packaged-claim reader (HLD-C §3.10): a runtime gets exactly its own claim, unchanged, from the manifest
 * packaged with it, and gets no claim at all when the manifest is missing, duplicated or invalid.
 */
class PackagedClaimTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * The claim block is returned exactly as packaged; the catalog block is ignored.
     */
    @Test
    void parseReturnsTheClaimBlockUnchanged() throws Exception {
        Claim claim = Fixtures.validProviderClaim();

        assertThat(PackagedClaim.parse(manifest(claim))).isEqualTo(claim);
    }

    /**
     * Other components' manifests on the same classpath are skipped; the identity match is returned.
     */
    @Test
    void loadSelectsTheClaimByIdentity(@TempDir Path dir) throws Exception {
        Claim own = Fixtures.validProviderClaim();
        Claim other = withUri(Fixtures.validProviderClaim("other.capability"), "urn:other");

        try (URLClassLoader loader = loader(jarRoot(dir, "own", own), jarRoot(dir, "other", other))) {
            assertThat(PackagedClaim.load(loader, URI.create("urn:provider"))).isEqualTo(own);
            assertThat(PackagedClaim.load(loader, URI.create("urn:other"))).isEqualTo(other);
        }
    }

    /**
     * A runtime whose claim is not packaged has no claim to report, so loading fails rather than inventing one.
     */
    @Test
    void loadFailsWhenNoManifestCarriesTheClaim(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = loader(jarRoot(dir, "other", withUri(Fixtures.validProviderClaim(), "urn:other")))) {
            assertThatExceptionOfType(PackagedClaim.PackagedClaimException.class)
                    .isThrownBy(() -> PackagedClaim.load(loader, URI.create("urn:provider")))
                    .withMessageContaining("carries the claim urn:provider");
        }
    }

    /**
     * Two artifacts carrying the same component's claim are ambiguous, so neither is chosen.
     */
    @Test
    void loadFailsWhenTwoManifestsCarryTheClaim(@TempDir Path dir) throws Exception {
        Claim claim = Fixtures.validProviderClaim();
        try (URLClassLoader loader = loader(jarRoot(dir, "a", claim), jarRoot(dir, "b", claim))) {
            assertThatExceptionOfType(PackagedClaim.PackagedClaimException.class)
                    .isThrownBy(() -> PackagedClaim.load(loader, URI.create("urn:provider")))
                    .withMessageContaining("2 copies");
        }
    }

    /**
     * An invalid packaged claim is refused with the validator's diagnostics.
     */
    @Test
    void parseRefusesAnInvalidClaim() throws Exception {
        Claim claim = Fixtures.validProviderClaim();
        Claim noRefusals = new Claim(claim.identity(), claim.domain(), List.of(), claim.dependencies(), claim.offers(),
                claim.conflictResolution(), claim.negotiationSurface(), claim.integrationPorts(), claim.faults(),
                claim.metadata());

        assertThatExceptionOfType(PackagedClaim.PackagedClaimException.class)
                .isThrownBy(() -> PackagedClaim.parse(manifest(noRefusals)))
                .satisfies(ex -> assertThat(ex.diagnostics()).isNotEmpty());
    }

    /**
     * A manifest without a claim block carries no claim.
     */
    @Test
    void parseRefusesAManifestWithoutAClaimBlock() {
        assertThatExceptionOfType(PackagedClaim.PackagedClaimException.class)
                .isThrownBy(() -> PackagedClaim.parse("catalog: {}\n".getBytes()))
                .withMessageContaining("has no claim block");
    }

    /**
     * Manifest fixture: the two-block catalog manifest a component packages.
     */
    private static byte[] manifest(Claim claim) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("claim", claim);
        manifest.put("catalog", Map.of("lifecycle", Map.of("status", "ACTIVE")));
        return YAML.writeValueAsBytes(manifest);
    }

    /**
     * Artifact fixture: a directory laid out like an exploded jar holding one manifest.
     */
    private static Path jarRoot(Path dir, String name, Claim claim) throws Exception {
        Path root = dir.resolve(name);
        Path manifest = root.resolve(PackagedClaim.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, manifest(claim));
        return root;
    }

    /**
     * Class loader fixture over exploded-jar directories, isolated from the test classpath.
     */
    private static URLClassLoader loader(Path... roots) throws Exception {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            urls[i] = roots[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    /**
     * Claim fixture: the same claim under another identity URI.
     */
    private static Claim withUri(Claim claim, String uri) {
        Identity identity = claim.identity();
        return new Claim(new Identity(URI.create(uri), identity.name(), identity.kind(), identity.version(),
                identity.publisher(), identity.publisherUri()), claim.domain(), claim.refusals(), claim.dependencies(),
                claim.offers(), claim.conflictResolution(), claim.negotiationSurface(), claim.integrationPorts(),
                claim.faults(), claim.metadata());
    }
}
