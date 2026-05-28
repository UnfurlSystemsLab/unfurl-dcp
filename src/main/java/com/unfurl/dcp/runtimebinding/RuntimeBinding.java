package com.unfurl.dcp.runtimebinding;

import java.net.URI;

public record RuntimeBinding(
        URI bindingId,
        URI contractId,
        String contractVersion,
        TargetEnvironment targetEnvironment,
        ProviderInstance providerInstance,
        ConsumerInstance consumerInstance,
        RuntimePolicy runtimePolicy,
        Configuration configuration,
        DeploymentControls deploymentControls,
        Lifecycle lifecycle
) {
}
