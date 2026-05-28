package com.unfurl.dcp.trust;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public record VerificationKeySet(Map<String, VerificationKey> keysById) {
    public VerificationKeySet {
        keysById = keysById == null ? Map.of() : Map.copyOf(keysById);
    }

    public static VerificationKeySet of(Collection<VerificationKey> keys) {
        if (keys == null) {
            return new VerificationKeySet(Map.of());
        }
        return new VerificationKeySet(keys.stream().collect(Collectors.toMap(VerificationKey::keyId, Function.identity())));
    }

    public Optional<VerificationKey> find(String keyId) {
        return Optional.ofNullable(keysById.get(keyId));
    }
}
