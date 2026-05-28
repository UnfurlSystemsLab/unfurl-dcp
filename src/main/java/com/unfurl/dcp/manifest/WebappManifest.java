package com.unfurl.dcp.manifest;

import jakarta.validation.constraints.NotEmpty;

import java.net.URI;
import java.util.List;

public record WebappManifest(
        URI componentUri,
        String componentVersion,
        String routePrefix,
        @NotEmpty List<Route> routes,
        Navigation navigation,
        @NotEmpty List<String> permissions,
        ThemeContribution themeContribution,
        Bootstrap bootstrap
) {
    public WebappManifest {
        routes = routes == null ? List.of() : List.copyOf(routes);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
