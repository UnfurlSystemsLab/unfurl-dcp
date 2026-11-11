package com.unfurl.dcp.host;

import com.unfurl.dcp.contract.Binding;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.spi.CapabilityRegistrar;
import com.unfurl.dcp.spi.ContractInvocableFactory;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runs component code with the component's class loader as the thread's context class loader (LLD, <em>In-Process
 * Component Host</em>, component code and the context class loader). Libraries that look classes or services up
 * through the context loader therefore stay inside the component's pinned set instead of reaching the host classpath.
 * The previous context loader is always restored.
 *
 * <p>It also supplies the Decorators the host puts around a component's factory, invocables and registrar, so every
 * call the host or the broker makes into the component goes through {@link #call}.
 */
final class ComponentScope {
    private final ClassLoader loader;

    /** @param loader the component's pinned-artifact class loader */
    ComponentScope(ClassLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** Runs {@code action} under the component's loader and returns its result. */
    <T> T call(Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Runs {@code action} under the component's loader. */
    void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    /**
     * Marks a failure that came from component code called during admission, so the host can tell it from a failure of
     * its own collaborators (for example an audit sink) inside the same broker call.
     */
    static final class ComponentCodeFailure extends RuntimeException {
        ComponentCodeFailure(String what, Throwable cause) {
            super(what + ": " + cause, cause);
        }
    }

    /** Runs {@code action} in scope; anything it throws is rethrown as a {@link ComponentCodeFailure}. */
    <T> T admissionCall(String what, Supplier<T> action) {
        try {
            return call(action);
        } catch (RuntimeException | Error failure) {
            throw new ComponentCodeFailure(what, failure);
        }
    }

    /**
     * Decorator: a factory whose {@code create} runs in scope and whose invocables are scoped too. A failure of the
     * component's factory, or a {@code null} invocable, is a {@link ComponentCodeFailure}.
     */
    ContractInvocableFactory scoped(ContractInvocableFactory factory) {
        return (CompositionContract contract, Binding binding, ExecutionContext context) -> {
            ContractInvocable invocable = admissionCall("the component's factory failed",
                    () -> factory.create(contract, binding, context));
            if (invocable == null) {
                throw new ComponentCodeFailure("the component's factory failed",
                        new IllegalStateException("it returned no invocable"));
            }
            return new ScopedInvocable(invocable);
        };
    }

    /** Decorator: runs each invocation of a component's invocable in scope. */
    private final class ScopedInvocable implements ContractInvocable {
        private final ContractInvocable delegate;

        private ScopedInvocable(ContractInvocable delegate) {
            this.delegate = delegate;
        }

        @Override public String contractId() {
            return call(delegate::contractId);
        }

        @Override public String contractVersion() {
            return call(delegate::contractVersion);
        }

        @Override public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
            return call(() -> delegate.invoke(invocation, context));
        }
    }

    /**
     * Decorator over a component's registrar: runs {@code register}/{@code unregister} in scope and records each
     * capability name <em>before</em> delegating {@code register}, so every attempted registration is known for
     * rollback, including one whose {@code register} failed part-way (LLD, <em>Rollback and close</em>).
     */
    static final class TrackingRegistrar implements CapabilityRegistrar {
        private final CapabilityRegistrar delegate;
        private final ComponentScope scope;
        private final List<String> attempted = new ArrayList<>();

        TrackingRegistrar(CapabilityRegistrar delegate, ComponentScope scope) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.scope = scope;
        }

        /** Records the attempt, then registers through the component's registrar; its failure is a {@link ComponentCodeFailure}. */
        @Override public void register(String capabilityName, ContractInvocable invocable, ExecutionContext context) {
            synchronized (attempted) {
                attempted.add(capabilityName);
            }
            scope.admissionCall("the component's registrar failed to register " + capabilityName, () -> {
                delegate.register(capabilityName, invocable, context);
                return null;
            });
        }

        /** Unregisters through the component's registrar; attempts stay recorded so rollback is repeatable. */
        @Override public void unregister(String capabilityName, ExecutionContext context) {
            scope.run(() -> delegate.unregister(capabilityName, context));
        }

        /** Returns every capability name whose registration was attempted, in order. */
        List<String> attempted() {
            synchronized (attempted) {
                return List.copyOf(attempted);
            }
        }
    }
}
