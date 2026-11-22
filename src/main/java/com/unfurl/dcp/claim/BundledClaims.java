package com.unfurl.dcp.claim;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Repository (read-only): the provider claims a deployment bundle carries beside its contracts, as
 * {@code claims/*.claim.{json,yaml,yml}} (DCP HLD-C2 G.1 input 2; written by Fabric's runtime bundle writer).
 *
 * <p>Every file is parsed with the public snake_case wire settings that {@link PackagedClaim} uses and validated by
 * {@link ClaimValidator}. The load is all or nothing: a file that does not parse, a claim with validation errors, or two
 * files carrying the same identity fail the whole load, because a reader that silently skipped a claim could later
 * mistake a missing provider for an absent one. This reader does not check pins; callers compare
 * {@link ClaimHash#canonical} with each contract's pinned hash.
 *
 * <p>It replaces the private readers each runtime kept (DCP LLD, <em>Bundled claims reader</em>).
 */
public final class BundledClaims {
    private static final ObjectMapper JSON = configure(new ObjectMapper());
    private static final ObjectMapper YAML = configure(new ObjectMapper(new YAMLFactory()));

    private BundledClaims() {
    }

    /**
     * Loads every claim file of a directory.
     *
     * @param directory the bundle's {@code claims/} directory; an absent directory is an empty result
     * @return the claims keyed by identity, sorted by key, unmodifiable
     * @throws BundledClaimsException naming the file when any file cannot be read, does not parse, is invalid, or
     *                                repeats another file's identity
     */
    public static Map<ClaimKey, Claim> loadDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return Map.of();
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(Files::isRegularFile).filter(BundledClaims::isClaimFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        } catch (IOException ex) {
            throw new BundledClaimsException("unable to list " + directory + ": " + ex.getMessage(), ex);
        }
        Map<ClaimKey, Claim> claims = new TreeMap<>(Comparator.comparing(ClaimKey::toString));
        Map<ClaimKey, Path> origins = new TreeMap<>(Comparator.comparing(ClaimKey::toString));
        ClaimValidator validator = new ClaimValidator();
        for (Path file : files) {
            Claim claim = read(file);
            SchemaValidationReport report = validator.validate(claim);
            if (!report.valid()) {
                throw new BundledClaimsException("the claim in " + file.getFileName() + " is invalid: " + report.diagnostics(), null);
            }
            ClaimKey key = ClaimKey.of(claim);
            Path previous = origins.putIfAbsent(key, file);
            if (previous != null) {
                throw new BundledClaimsException(previous.getFileName() + " and " + file.getFileName()
                        + " both carry the claim " + key, null);
            }
            claims.put(key, claim);
        }
        return Collections.unmodifiableMap(claims);
    }

    /** Parser: chooses JSON or YAML by file extension. */
    private static Claim read(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            return (name.endsWith(".json") ? JSON : YAML).readValue(Files.readAllBytes(file), Claim.class);
        } catch (IOException ex) {
            throw new BundledClaimsException("unable to read the claim in " + file.getFileName() + ": " + ex.getMessage(), ex);
        }
    }

    /** Filter: claim files by their conventional suffixes. */
    private static boolean isClaimFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".claim.json") || name.endsWith(".claim.yaml") || name.endsWith(".claim.yml");
    }

    /** Builder: the public claim wire settings, identical to {@link PackagedClaim}'s. */
    private static ObjectMapper configure(ObjectMapper mapper) {
        return mapper.registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Failure of a bundled-claims load, naming the file. */
    public static final class BundledClaimsException extends IllegalStateException {
        /** Constructor: a message naming the file, and the underlying cause when there is one. */
        public BundledClaimsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
