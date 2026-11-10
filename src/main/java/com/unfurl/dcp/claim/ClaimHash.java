package com.unfurl.dcp.claim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Canonical claim hash: the single definition of the {@code claimHash} a composer freezes into a contract's provider
 * pin and a host recomputes from the packaged claim before admitting it (LLD, <em>In-Process Component Host</em>). The
 * composer and every host must agree byte for byte, so the rule lives here rather than in either.
 *
 * <p>The claim is serialized as YAML with map entries and properties sorted, line splitting and the document marker
 * off, {@code NON_NULL} inclusion and ISO-8601 dates, using the record component names; the hash is the lower-case hex
 * SHA-256 of those UTF-8 bytes. These are exactly the bytes Fabric's catalog codec produced before it delegated here, so
 * existing pins stay valid. Changing any setting below changes every claim hash and is a breaking protocol change.
 */
public final class ClaimHash {
    private static final ObjectMapper CANONICAL = canonicalMapper();

    /** Utility class: static rule only. */
    private ClaimHash() { }

    /**
     * Computes the canonical hash of one claim.
     *
     * @param claim the claim, as validated by {@link ClaimValidator}
     * @return the lower-case hex SHA-256 of the claim's canonical YAML
     * @throws IllegalArgumentException when the claim cannot be serialized
     */
    public static String canonical(Claim claim) {
        Objects.requireNonNull(claim, "claim is required");
        try {
            byte[] canonical = CANONICAL.writeValueAsBytes(claim);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("unable to canonicalize claim: " + ex.getOriginalMessage(), ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** Builder: the fixed canonical mapper described in the class comment. Immutable once built, so it is shared. */
    private static ObjectMapper canonicalMapper() {
        YAMLFactory yaml = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .disable(YAMLGenerator.Feature.SPLIT_LINES);
        ObjectMapper mapper = new ObjectMapper(yaml);
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }
}
