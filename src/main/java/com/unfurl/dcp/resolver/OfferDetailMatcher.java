package com.unfurl.dcp.resolver;

import com.unfurl.dcp.claim.Offer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Strategy: deterministic subset matcher for DCP {@link Offer#offerInterface()} details.
 * Hosts use this when a consumer need requires governed capability structure, such as an
 * {@code agent.run} offer whose {@code execution_modes} must include {@code harness}.
 */
public final class OfferDetailMatcher {

    /**
     * Offer matcher: checks whether an offer's interface details satisfy the required detail subset.
     *
     * @param offer           provider offer to inspect.
     * @param requiredDetails required detail subset, empty meaning unconstrained.
     * @return true when the offer satisfies the required subset.
     */
    public boolean matches(Offer offer, Map<String, Object> requiredDetails) {
        if (requiredDetails == null || requiredDetails.isEmpty()) {
            return true;
        }
        if (offer == null || offer.offerInterface() == null) {
            return false;
        }
        return matches(offer.offerInterface().details(), requiredDetails);
    }

    /**
     * Map matcher: recursively checks that every required key/value appears in the offered map.
     *
     * @param offered         offered detail map.
     * @param requiredDetails required detail subset.
     * @return true when all required values match.
     */
    public boolean matches(Map<String, Object> offered, Map<String, Object> requiredDetails) {
        if (requiredDetails == null || requiredDetails.isEmpty()) {
            return true;
        }
        if (offered == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : requiredDetails.entrySet()) {
            if (!valueMatches(offered.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Value matcher: applies equality for scalars, collection containment for required
     * collections, array containment for required arrays, and recursive subset matching for maps.
     *
     * @param offered  offered value.
     * @param required required value or subset.
     * @return true when the offered value satisfies the required value.
     */
    private boolean valueMatches(Object offered, Object required) {
        if (required instanceof Map<?, ?> requiredMap) {
            if (!(offered instanceof Map<?, ?> offeredMap)) {
                return false;
            }
            return matches(stringifyKeys(offeredMap), stringifyKeys(requiredMap));
        }
        if (required instanceof Collection<?> requiredCollection) {
            if (!(offered instanceof Collection<?> offeredCollection)) {
                return false;
            }
            return offeredCollection.containsAll(requiredCollection);
        }
        if (required instanceof Object[] requiredArray) {
            if (!(offered instanceof Collection<?> offeredCollection)) {
                return false;
            }
            return offeredCollection.containsAll(List.of(requiredArray));
        }
        return Objects.equals(offered, required);
    }

    /**
     * Adapter: normalizes map keys to strings because DCP detail objects originate as JSON/YAML maps.
     *
     * @param map source map.
     * @return copy with stringified keys.
     */
    private Map<String, Object> stringifyKeys(Map<?, ?> map) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return Map.copyOf(normalized);
    }
}
