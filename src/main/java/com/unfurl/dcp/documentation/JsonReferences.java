package com.unfurl.dcp.documentation;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * Utility (Visitor over a Jackson tree): the one implementation of how documentation code finds, resolves and rewrites
 * {@code $ref}s and finds forbidden keywords (DCP HLD-C2 A.12, G.2). Validation, embedding and the render guard all use
 * it, so they agree on what a reference is.
 *
 * <p>A reference is any {@code "$ref"} member whose value is a string. Keys inside a {@code properties} or
 * {@code patternProperties} map are property names, not keywords, so a property literally called {@code $ref} is not
 * mistaken for a reference.
 */
final class JsonReferences {
    /** Keywords that change resolution scope; shipped schemas must not use them. */
    static final Set<String> FORBIDDEN_KEYWORDS = Set.of("$id", "$anchor", "$dynamicRef", "$dynamicAnchor",
            "$recursiveRef", "$recursiveAnchor");
    private static final Set<String> NAME_MAPS = Set.of("properties", "patternProperties", "$defs", "definitions",
            "dependentSchemas");

    private JsonReferences() { }

    /** Visits every {@code $ref} string, with the JSON Pointer of the member that holds it. */
    static void forEachRef(JsonNode node, BiConsumer<String, String> visitor) {
        walk(node, "", false, (pointer, key, value) -> {
            if ("$ref".equals(key) && value.isTextual()) visitor.accept(pointer, value.asText());
        });
    }

    /** Visits every forbidden keyword, with the JSON Pointer of the member. */
    static void forEachForbiddenKeyword(JsonNode node, BiConsumer<String, String> visitor) {
        walk(node, "", false, (pointer, key, value) -> {
            if (FORBIDDEN_KEYWORDS.contains(key)) visitor.accept(pointer, key);
        });
    }

    /**
     * Resolves a local reference against a document.
     *
     * @param root the document
     * @param ref  a reference of the form {@code #} or {@code #/<pointer>}, possibly percent-encoded
     * @return whether it names an existing node
     */
    static boolean resolves(JsonNode root, String ref) {
        if (ref == null || !ref.startsWith("#")) return false;
        String fragment = URLDecoder.decode(ref.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8);
        if (fragment.isEmpty()) return true;
        if (!fragment.startsWith("/")) return false;
        try {
            return !root.at(JsonPointer.compile(fragment)).isMissingNode();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * Returns a deep copy with every {@code $ref} string rewritten.
     *
     * @param node    the tree to copy
     * @param rewrite maps an old reference to its new one
     */
    static JsonNode rewriteRefs(JsonNode node, UnaryOperator<String> rewrite) {
        JsonNode copy = node.deepCopy();
        rewriteInPlace(copy, rewrite, false);
        return copy;
    }

    /**
     * Returns a deep copy of a product API document (or any part of it) with every {@code #/components/<section>/<name>}
     * reference renamed to {@code #/components/<section>/<apiId>.<name>} (DCP HLD-C2 G.2). The renderer merges
     * documents with it, and the validator compares path metadata with it, so both see the same rewritten values.
     */
    static JsonNode prefixComponentRefs(JsonNode node, String apiId) {
        String prefix = apiId + ".";
        return rewriteRefs(node, ref -> {
            if (!ref.startsWith("#/components/")) return ref;
            String rest = ref.substring("#/components/".length());
            int slash = rest.indexOf('/');
            return slash < 0 ? ref : "#/components/" + rest.substring(0, slash + 1) + prefix + rest.substring(slash + 1);
        });
    }

    /** JSON-Pointer escape of one reference token. */
    static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    /** Recursive rewrite; {@code namesOnly} marks a map whose keys are names, not keywords. */
    private static void rewriteInPlace(JsonNode node, UnaryOperator<String> rewrite, boolean namesOnly) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!namesOnly && "$ref".equals(field.getKey()) && field.getValue().isTextual()) {
                    field.setValue(TextNode.valueOf(rewrite.apply(field.getValue().asText())));
                } else {
                    rewriteInPlace(field.getValue(), rewrite, !namesOnly && NAME_MAPS.contains(field.getKey()));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(item -> rewriteInPlace(item, rewrite, false));
        }
    }

    /** Member visitor: (pointer of the member, member key, member value). */
    @FunctionalInterface
    private interface MemberVisitor {
        void visit(String pointer, String key, JsonNode value);
    }

    /** Recursive walk; {@code namesOnly} marks a map whose keys are names, not keywords. */
    private static void walk(JsonNode node, String pointer, boolean namesOnly, MemberVisitor visitor) {
        if (node instanceof ObjectNode object) {
            object.fields().forEachRemaining(field -> {
                String child = pointer + "/" + escape(field.getKey());
                if (!namesOnly) visitor.visit(child, field.getKey(), field.getValue());
                walk(field.getValue(), child, !namesOnly && NAME_MAPS.contains(field.getKey()), visitor);
            });
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                walk(array.get(i), pointer + "/" + i, false, visitor);
            }
        }
    }
}
