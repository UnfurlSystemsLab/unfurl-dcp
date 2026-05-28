package com.unfurl.dcp.manifest;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Concern;
import com.unfurl.dcp.claim.DecisionOwned;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class WebappManifestValidator {
    public SchemaValidationReport validate(WebappManifest manifest, Claim claim) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (manifest == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "manifest is required", "$"));
            return new SchemaValidationReport(diagnostics);
        }
        if (claim == null || claim.identity() == null) {
            diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "matching claim is required", "claim"));
        } else {
            if (!Objects.equals(manifest.componentUri(), claim.identity().uri())) {
                diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "component_uri must match claim identity uri", "component_uri"));
            }
            if (!Objects.equals(manifest.componentVersion(), claim.identity().version())) {
                diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "component_version must match claim identity version", "component_version"));
            }
            Set<String> derivablePermissions = derivablePermissions(claim);
            for (String permission : manifest.permissions()) {
                if (!derivablePermissions.contains(permission)) {
                    diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "permission is not derivable from claim concerns or offers", "permissions"));
                }
            }
        }
        if (manifest.themeContribution() != null && manifest.themeContribution().mode() != ThemeMode.SUGGESTIVE) {
            diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "theme mode must be SUGGESTIVE", "theme_contribution.mode"));
        }
        return new SchemaValidationReport(diagnostics);
    }

    private Set<String> derivablePermissions(Claim claim) {
        Set<String> permissions = new HashSet<>();
        for (Offer offer : claim.offers()) {
            permissions.add("capability:" + offer.capability());
        }
        if (claim.domain() != null) {
            for (Concern concern : claim.domain().concerns()) {
                if (concern.concern() != null) {
                    permissions.add("concern:" + concern.concern());
                }
                for (DecisionOwned decision : concern.ownsDecisions()) {
                    permissions.add("decision:" + decision.decision());
                }
            }
        }
        return permissions;
    }
}
