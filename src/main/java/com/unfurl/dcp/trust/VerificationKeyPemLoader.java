package com.unfurl.dcp.trust;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * Loader/Builder: reads PEM public keys into a DCP {@link VerificationKeySet}. It is deliberately
 * protocol-local and dependency-free so product runtimes such as Flow can verify frozen DCP
 * contracts without importing Fabric signing utilities.
 */
public final class VerificationKeyPemLoader {
    private static final List<String> KEY_ALGORITHMS = List.of("EC", "RSA", "Ed25519");

    /**
     * Load every {@code *.pem} file in deterministic filename order and key them by SHA-256
     * SubjectPublicKeyInfo fingerprint.
     */
    public VerificationKeySet loadDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("verification key directory is required: " + directory);
        }
        List<Path> pemFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.pem")) {
            for (Path path : stream) {
                pemFiles.add(path);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("unable to list verification key directory: " + directory, ex);
        }
        pemFiles.sort(Comparator.comparing(path -> path.getFileName().toString()));
        List<VerificationKey> keys = pemFiles.stream().map(this::load).toList();
        return VerificationKeySet.of(keys);
    }

    /**
     * Load one PEM public key and key it by its stable SHA-256 fingerprint.
     */
    public VerificationKey load(Path pemFile) {
        PublicKey publicKey = publicKey(pemFile);
        return new VerificationKey(fingerprint(publicKey), publicKey);
    }

    /**
     * PEM parser: decodes the standard {@code BEGIN PUBLIC KEY} SubjectPublicKeyInfo wrapper and
     * tries the JDK key factories used by the signing tools.
     */
    private PublicKey publicKey(Path pemFile) {
        try {
            String pem = Files.readString(pemFile);
            String base64 = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(base64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            for (String algorithm : KEY_ALGORITHMS) {
                try {
                    return KeyFactory.getInstance(algorithm).generatePublic(spec);
                } catch (Exception ignored) {
                    // Try the next JDK-supported key algorithm.
                }
            }
            throw new IllegalArgumentException("unsupported public key algorithm: " + pemFile);
        } catch (IOException ex) {
            throw new IllegalStateException("unable to read verification key: " + pemFile, ex);
        }
    }

    /**
     * Fingerprint helper: computes the key id used by DCP signatures from encoded public-key bytes.
     */
    private String fingerprint(PublicKey publicKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("unable to fingerprint verification key", ex);
        }
    }
}
