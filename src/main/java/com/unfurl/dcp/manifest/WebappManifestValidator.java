package com.unfurl.dcp.manifest;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.SchemaValidationReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        }
        if (manifest.themeContribution() != null && manifest.themeContribution().mode() != ThemeMode.SUGGESTIVE) {
            diagnostics.add(Diagnostic.error(ErrorCode.MANIFEST_MISMATCH, "theme mode must be SUGGESTIVE", "theme_contribution.mode"));
        }
        return new SchemaValidationReport(diagnostics);
    }
}
