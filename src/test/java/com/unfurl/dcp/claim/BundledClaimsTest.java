package com.unfurl.dcp.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.testing.DocumentationFixtures;
import com.unfurl.dcp.testing.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the bundled-claims reader: every {@code *.claim.json|yaml} file is read and validated, keyed by identity, and
 * the load is all or nothing.
 */
class BundledClaimsTest {
    private static final ObjectMapper JSON = mapper(new ObjectMapper());
    private static final ObjectMapper YAML = mapper(new ObjectMapper(new YAMLFactory()));

    /** JSON and YAML claim files are read unchanged, keyed by identity; other files are ignored. */
    @Test
    void readsEveryClaimFile(@TempDir Path dir) throws Exception {
        Claim provider = DocumentationFixtures.documentedClaim(Fixtures.validProviderClaim());
        Claim other = DocumentationFixtures.withIdentity(Fixtures.validProviderClaim("x.y"), "urn:other", "2.0.0");
        Files.write(dir.resolve("provider.claim.json"), JSON.writeValueAsBytes(provider));
        Files.write(dir.resolve("other.claim.yaml"), YAML.writeValueAsBytes(other));
        Files.writeString(dir.resolve("README.md"), "not a claim");

        Map<ClaimKey, Claim> claims = BundledClaims.loadDirectory(dir);

        assertThat(claims).containsOnlyKeys(ClaimKey.of(provider), ClaimKey.of(other));
        assertThat(claims.get(ClaimKey.of(provider))).isEqualTo(provider);
        assertThat(ClaimHash.canonical(claims.get(ClaimKey.of(provider)))).isEqualTo(ClaimHash.canonical(provider));
    }

    /** An absent directory is an empty bundle. */
    @Test
    void absentDirectoryIsEmpty(@TempDir Path dir) {
        assertThat(BundledClaims.loadDirectory(dir.resolve("missing"))).isEmpty();
    }

    /** Two files with one identity, an unparsable file, or an invalid claim fail the whole load. */
    @Test
    void loadIsAllOrNothing(@TempDir Path dir) throws Exception {
        Path duplicate = Files.createDirectory(dir.resolve("dup"));
        Files.write(duplicate.resolve("a.claim.json"), JSON.writeValueAsBytes(Fixtures.validProviderClaim()));
        Files.write(duplicate.resolve("b.claim.json"), JSON.writeValueAsBytes(Fixtures.validProviderClaim()));
        assertThatThrownBy(() -> BundledClaims.loadDirectory(duplicate)).hasMessageContaining("both carry the claim");

        Path broken = Files.createDirectory(dir.resolve("broken"));
        Files.writeString(broken.resolve("a.claim.json"), "{not json");
        assertThatThrownBy(() -> BundledClaims.loadDirectory(broken)).hasMessageContaining("a.claim.json");

        Path invalid = Files.createDirectory(dir.resolve("invalid"));
        Claim noRefusals = new Claim(Fixtures.validProviderClaim().identity(), Fixtures.validProviderClaim().domain(),
                java.util.List.of(), Fixtures.validProviderClaim().dependencies(), Fixtures.validProviderClaim().offers(),
                Fixtures.validProviderClaim().conflictResolution(), null, Fixtures.validProviderClaim().integrationPorts(),
                Fixtures.validProviderClaim().faults(), Fixtures.validProviderClaim().metadata());
        Files.write(invalid.resolve("a.claim.json"), JSON.writeValueAsBytes(noRefusals));
        assertThatThrownBy(() -> BundledClaims.loadDirectory(invalid)).hasMessageContaining("is invalid");
    }

    /** Helper: the public claim wire settings. */
    private static ObjectMapper mapper(ObjectMapper base) {
        return base.registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
