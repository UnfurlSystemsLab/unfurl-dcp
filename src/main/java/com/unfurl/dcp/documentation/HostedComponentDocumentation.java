package com.unfurl.dcp.documentation;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.host.HostReport;
import com.unfurl.dcp.host.ProviderArtifactPin;

import java.util.Objects;

/**
 * Value Object: what a host verified when it admitted one in-process component, as the host view documents it (DCP
 * HLD-C2 G.2 host view). It is never reconstructed: {@link #from} takes it straight from the host's admission evidence.
 *
 * @param claim     the packaged claim the host checked against the pin
 * @param pin       the pin it was checked against
 * @param artifacts the component's shipped artifacts, read from the verified jar snapshot
 */
public record HostedComponentDocumentation(Claim claim, ProviderArtifactPin pin, DocumentationArtifactStore artifacts) {
    /** Validating constructor: every part is required. */
    public HostedComponentDocumentation {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(artifacts, "artifacts");
    }

    /** Factory: from DCP in-process host admission evidence. */
    public static HostedComponentDocumentation from(HostReport.AdmittedComponent admitted) {
        Objects.requireNonNull(admitted, "admitted");
        return new HostedComponentDocumentation(admitted.claim(), admitted.pin(),
                PackagedArtifactStore.fromResources(admitted.resources()));
    }
}
