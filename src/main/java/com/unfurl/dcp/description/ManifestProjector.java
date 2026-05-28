package com.unfurl.dcp.description;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.manifest.*;

import java.util.List;

public final class ManifestProjector {
    public WebappManifest toManifest(ComponentDescription description) {
        List<String> permissions = description.offers().stream()
                .map(Offer::capability)
                .map(capability -> "capability:" + capability)
                .toList();
        return new WebappManifest(
                description.identity().uri(),
                description.identity().version(),
                "/",
                List.of(),
                new Navigation(List.of()),
                permissions,
                new ThemeContribution(ThemeMode.SUGGESTIVE, java.util.Map.of()),
                new Bootstrap(false, true));
    }
}
