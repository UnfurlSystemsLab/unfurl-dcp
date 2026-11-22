package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.harrel.jsonschema.Dialects;
import dev.harrel.jsonschema.InvalidSchemaException;
import dev.harrel.jsonschema.JsonSchemaException;
import dev.harrel.jsonschema.ValidatorFactory;
import dev.harrel.jsonschema.SchemaResolver;
import dev.harrel.jsonschema.providers.JacksonNode;
import java.util.Comparator;
import java.util.List;

/** Adapter: validates schema syntax with the bundled 2020-12 dialect; no remote resolver or logging facade is used. */
final class HarrelSchemaSyntaxValidator implements SchemaSyntaxValidator {
    /** Validates each document in an isolated registry, returning stable diagnostics rather than vendor exceptions. */
    @Override
    public List<Violation> validate(JsonNode schema) {
        try {
            new ValidatorFactory()
                    .withDefaultDialect(new Dialects.Draft2020Dialect())
                    // The factory's fallback resolves official dialect resources from its jar, never the network.
                    .withSchemaResolver(uri -> uri.startsWith("https://json-schema.org/draft/2020-12/")
                            ? SchemaResolver.Result.empty() : rejectExternal())
                    .createValidator().registerSchema(new JacksonNode(schema));
            return List.of();
        } catch (InvalidSchemaException invalid) {
            return invalid.getErrors().stream()
                    .map(error -> new Violation(error.getInstanceLocation(), error.getError()))
                    .sorted(Comparator.comparing(Violation::pointer).thenComparing(Violation::message)).toList();
        } catch (JsonSchemaException | IllegalArgumentException invalid) {
            return List.of(new Violation("", "Schema syntax or reference is invalid: " + invalid.getMessage()));
        }
    }

    /** Refuses every resource outside the bundled dialect; caller references are also checked by DCP's local-ref gate. */
    private static SchemaResolver.Result rejectExternal() {
        throw new IllegalArgumentException("External schema resolution is forbidden");
    }
}
