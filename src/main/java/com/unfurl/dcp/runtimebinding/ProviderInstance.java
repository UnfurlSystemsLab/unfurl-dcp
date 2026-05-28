package com.unfurl.dcp.runtimebinding;

import java.net.URI;

public record ProviderInstance(
        URI componentUri,
        String componentVersion,
        String instanceName,
        DeploymentKind deploymentKind,
        URI baseUrl,
        ConfigRef baseUrlRef,
        SecretRef credentialsRef
) {
}
