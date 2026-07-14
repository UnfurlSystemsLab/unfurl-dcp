package com.unfurl.dcp.runtimebinding;

import java.net.URI;

/**
 * Schema record: DCP runtime binding for one contract edge or aggregate binding node.
 *
 * <p>Pattern: Composite. A binding can be a leaf that wires one contract/provider instance, or an
 * aggregate parent whose {@link RuntimeBindingMetadata#extensions()} contains child binding ids
 * using the same containment keys as recursive DCP claims.
 */
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
        Lifecycle lifecycle,
        RuntimeBindingMetadata metadata
) {
}
