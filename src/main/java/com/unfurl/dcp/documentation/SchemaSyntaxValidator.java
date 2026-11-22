package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Port: checks schema syntax against the fixed draft 2020-12 dialect without resolving external resources. */
interface SchemaSyntaxValidator {
    /** Returns all syntax violations; an empty list means the schema conforms to the dialect's meta-schema. */
    List<Violation> validate(JsonNode schema);

    /** Value Object: an instance JSON Pointer into the supplied schema and its diagnostic explanation. */
    record Violation(String pointer, String message) { }
}
