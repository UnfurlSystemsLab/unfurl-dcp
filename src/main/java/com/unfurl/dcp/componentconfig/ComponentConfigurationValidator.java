package com.unfurl.dcp.componentconfig;

import com.unfurl.dcp.componentconfig.ConfigKey.OverridePolicy;
import com.unfurl.dcp.componentconfig.ConfigKey.Source;
import com.unfurl.dcp.componentconfig.EffectiveConfiguration.Origin;
import com.unfurl.dcp.runtimebinding.ConfigRef;
import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.substrate.policy.ExecutionContext;
import com.unfurl.substrate.ports.host.ConfigResolver;
import com.unfurl.substrate.ports.host.ResolvedConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Specification: merges a component's packaged configuration with a runtime binding's deployment values and applies
 * every rule of the LLD's <em>Merge and checks</em> table. It is the one rule set for both users:
 * <ul>
 *   <li>a composer calls {@link #check} before writing bindings, which decides everything that does not need a
 *       reference's value (a missing or unknown key, a fixed key, a key given twice, a bad inline value);</li>
 *   <li>an in-process host calls {@link #merge} at admission, before any component code runs, which also resolves
 *       {@code config://} references through the host's {@link ConfigResolver} and checks the resolved values.</li>
 * </ul>
 * All failures are collected and ordered by key, so one run lists every bad key. No failure detail repeats a value.
 */
public final class ComponentConfigurationValidator {
    private static final String CONFIG_SCHEME = "config://";
    private static final String VALUE = "value";

    /** Utility class: static rules only. */
    private ComponentConfigurationValidator() { }

    /**
     * Composer check: every rule decidable without resolving references. A key given as a reference is checked for
     * form ({@code config://}) and for being allowed at all; its value is checked later, by the host.
     *
     * @param schema     the component's packaged schema
     * @param deployment the binding's configuration; null means no deployment values
     * @return every failure found, ordered by key; empty when the deployment values are acceptable so far
     */
    public static List<ConfigFailure> check(ComponentConfigurationSchema schema, Configuration deployment) {
        return evaluate(schema, deployment, null, null, false).failures;
    }

    /**
     * Host merge: validates and combines packaged defaults with deployment values, resolving references.
     *
     * @param schema     the component's packaged schema
     * @param deployment the trusted runtime binding's configuration; null means no deployment values
     * @param resolver   the host's bound resolver, or null when the host binds none (references then fail)
     * @param context    the trusted context whose tenant scopes reference resolution
     * @return the immutable effective configuration
     * @throws ComponentConfigurationException carrying every failure; resolver exceptions are attached as suppressed
     */
    public static EffectiveConfiguration merge(ComponentConfigurationSchema schema, Configuration deployment,
                                               ConfigResolver resolver, ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        Evaluation evaluation = evaluate(schema, deployment, resolver, context, true);
        if (!evaluation.failures.isEmpty()) {
            ComponentConfigurationException failure = new ComponentConfigurationException(evaluation.failures);
            evaluation.resolverErrors.forEach(failure::addSuppressed);
            throw failure;
        }
        Set<String> secretKeys = new TreeSet<>();
        schema.keys().values().stream().filter(ConfigKey::secret).forEach(key -> secretKeys.add(key.name()));
        return new EffectiveConfiguration(true, evaluation.values, evaluation.origins, secretKeys);
    }

    /** Working state of one evaluation. */
    private static final class Evaluation {
        private final List<ConfigFailure> failures = new ArrayList<>();
        private final List<RuntimeException> resolverErrors = new ArrayList<>();
        private final Map<String, Object> values = new TreeMap<>();
        private final Map<String, Origin> origins = new TreeMap<>();

        private void fail(ConfigFailureCode code, String key, String detail) {
            failures.add(new ConfigFailure(code, key, detail));
        }
    }

    /** The shared rule walk; {@code resolve} is false for the composer check, which cannot see referenced values. */
    private static Evaluation evaluate(ComponentConfigurationSchema schema, Configuration deployment,
                                       ConfigResolver resolver, ExecutionContext context, boolean resolve) {
        Objects.requireNonNull(schema, "schema");
        Map<String, Object> inline = deployment == null ? Map.of() : deployment.values();
        Map<String, ConfigRef> references = deployment == null ? Map.of() : deployment.configRefs();
        Evaluation evaluation = new Evaluation();

        Set<String> supplied = new TreeSet<>(inline.keySet());
        supplied.addAll(references.keySet());
        for (String name : supplied) {
            if (!schema.keys().containsKey(name)) {
                evaluation.fail(ConfigFailureCode.CONFIG_KEY_UNKNOWN, name, "the component's schema declares no such key");
            }
        }

        for (ConfigKey key : schema.keys().values()) {
            String name = key.name();
            boolean hasInline = inline.containsKey(name);
            boolean hasReference = references.containsKey(name);
            if (hasInline && hasReference) {
                evaluation.fail(ConfigFailureCode.CONFIG_SOURCE_AMBIGUOUS, name, "given both inline and as a reference");
            } else if (!hasInline && !hasReference) {
                if (key.source() == Source.DEPLOYMENT) {
                    evaluation.fail(ConfigFailureCode.CONFIG_REQUIRED_MISSING, name, "the deployment must supply this key");
                } else {
                    evaluation.values.put(name, schema.defaults().get(name));
                    evaluation.origins.put(name, Origin.PACKAGED);
                }
            } else if (key.source() == Source.PACKAGED && key.override() == OverridePolicy.FIXED) {
                evaluation.fail(ConfigFailureCode.CONFIG_KEY_FIXED, name, "the component fixes this key; a deployment cannot change it");
            } else if (hasInline) {
                accept(schema, key, inline.get(name), Origin.INLINE, evaluation);
            } else {
                reference(schema, key, references.get(name), resolver, context, resolve, evaluation);
            }
        }
        evaluation.failures.sort(Comparator.comparing(ConfigFailure::key).thenComparing(ConfigFailure::code));
        return evaluation;
    }

    /** Handles a key given as a reference: checks its form, then (for a host) resolves it and accepts the value. */
    private static void reference(ComponentConfigurationSchema schema, ConfigKey key, ConfigRef reference,
                                  ConfigResolver resolver, ExecutionContext context, boolean resolve, Evaluation evaluation) {
        String name = key.name();
        String uri = reference == null ? null : reference.uri();
        if (uri == null || !uri.startsWith(CONFIG_SCHEME) || uri.length() == CONFIG_SCHEME.length()) {
            evaluation.fail(ConfigFailureCode.CONFIG_REF_UNRESOLVED, name, "the reference must be a config:// URI");
            return;
        }
        if (!resolve) return;
        if (resolver == null) {
            evaluation.fail(ConfigFailureCode.CONFIG_REF_UNRESOLVED, name, "the host binds no ConfigResolver");
            return;
        }
        Optional<ResolvedConfig> resolved;
        try {
            resolved = resolver.resolve(uri, context);
        } catch (RuntimeException refused) {
            evaluation.resolverErrors.add(refused);
            evaluation.fail(ConfigFailureCode.CONFIG_REF_UNRESOLVED, name,
                    "the resolver refused the reference (" + refused.getClass().getSimpleName() + ")");
            return;
        }
        if (resolved == null || resolved.isEmpty()) {
            evaluation.fail(ConfigFailureCode.CONFIG_REF_UNRESOLVED, name, "the reference does not exist");
            return;
        }
        ResolvedConfig document = resolved.get();
        if (!uri.equals(document.configRef())) {
            evaluation.fail(ConfigFailureCode.CONFIG_REF_UNRESOLVED, name, "the resolver answered for a different reference");
        } else if (document.values().size() != 1 || !document.values().containsKey(VALUE)) {
            evaluation.fail(ConfigFailureCode.CONFIG_REF_UNRESOLVED, name, "the reference must resolve to exactly { value: ... }");
        } else {
            accept(schema, key, document.values().get(VALUE), Origin.reference(uri, document.revision()), evaluation);
        }
    }

    /** Applies the key's type, bounds and tighten rule to one deployment value, recording the value or the failure. */
    private static void accept(ComponentConfigurationSchema schema, ConfigKey key, Object raw, Origin origin,
                               Evaluation evaluation) {
        try {
            Object value = key.normalize(raw);
            if (key.override() == OverridePolicy.TIGHTEN && key.widens(schema.defaults().get(key.name()), value)) {
                evaluation.fail(ConfigFailureCode.CONFIG_OVERRIDE_WIDENS, key.name(),
                        "the override is looser than the packaged default; only a stricter value is allowed");
                return;
            }
            evaluation.values.put(key.name(), value);
            evaluation.origins.put(key.name(), origin);
        } catch (ValueRejection rejection) {
            evaluation.fail(rejection.code(), key.name(), "the value (" + key.describe() + ") " + rejection.getMessage());
        }
    }
}
