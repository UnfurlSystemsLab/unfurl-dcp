package com.unfurl.dcp.host;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test control panel shared by the host tests and the synthetic component compiled at test time. Because it lives in a
 * shared API package, both sides see the same static state: a test flips a switch, and the component's activation and
 * registrar behave accordingly. This keeps one component source able to exercise every admission failure.
 */
public final class HostedSwitches {
    /** How many times the component's activation was constructed. */
    public static final AtomicInteger constructed = new AtomicInteger();
    /** Every registrar the component created, so a test can assert what is left registered. */
    public static final List<RecordingToolRegistrar> registrars = new CopyOnWriteArrayList<>();
    /** The claim version the activation reports. */
    public static volatile String claimVersion;
    /** The host dependencies the activation declares. */
    public static volatile Map<String, Set<Class<?>>> hostDependencies;
    /** Whether the activation offers a registrar of the test shape. */
    public static volatile boolean supportShape;
    /** Whether the activation's {@code factory} throws. */
    public static volatile boolean factoryThrows;
    /** A capability whose {@code register} stores the registration and then throws; null for none. */
    public static volatile String failRegisterFor;
    /** A capability whose {@code unregister} throws without removing it; null for none. */
    public static volatile String failUnregisterFor;
    /** Whether the registrar's switched failures are {@link AssertionError}s (an {@link Error}) rather than exceptions. */
    public static volatile boolean throwErrors;

    private HostedSwitches() { }

    /** Restores the behaviour of a well-behaved component. */
    public static void reset() {
        constructed.set(0);
        registrars.clear();
        claimVersion = "1.0.0";
        hostDependencies = Map.of("host.audit", Set.of(com.unfurl.substrate.ports.host.OperationAuditSink.class));
        supportShape = true;
        factoryThrows = false;
        failRegisterFor = null;
        failUnregisterFor = null;
        throwErrors = false;
    }

    /** Throws the switched failure: an {@link AssertionError} when {@link #throwErrors} is set, else an exception. */
    public static void fail(String message) {
        if (throwErrors) throw new AssertionError(message);
        throw new IllegalStateException(message);
    }
}
