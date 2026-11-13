package com.unfurl.dcp.host;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.trust.VerificationKeySet;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Repository (Composite): a signed composition as a host receives it, its verified aggregate root and exactly the children
 * the root names (LLD, <em>In-Process Component Host</em>). {@link VerifiedContracts} proves each envelope; this proves the
 * set:
 * <ul>
 *   <li>the root and every child verify as {@link VerifiedContracts} requires (signature, schema, signed-body equality);</li>
 *   <li>the children are exactly the root's transitive {@code childContractIds()} closure, matched by contract id, with
 *       none missing and none extra.</li>
 * </ul>
 * So deleting a child's file, such as an optional component's contract, cannot turn that component off: the load fails
 * with {@link HostFailureCode#CONTRACT_UNVERIFIED}, naming the missing child. A host decides whether an optional component
 * is in the composition from {@link #children()} only after this load succeeds.
 */
public final class VerifiedComposition {
    private final VerifiedContract root;
    private final VerifiedContracts children;

    private VerifiedComposition(VerifiedContract root, VerifiedContracts children) {
        this.root = root;
        this.children = children;
    }

    /**
     * Verifies a root and its children and checks that the children are exactly the root's closure.
     *
     * @param root     the aggregate root's envelope
     * @param children the children's envelopes, in any order
     * @param keys     the host's trusted verification keys
     * @throws ComponentHostException {@link HostFailureCode#CONTRACT_UNVERIFIED} on the first failing envelope, or naming
     *                                each missing or extra child
     */
    public static VerifiedComposition load(ContractSource root, List<ContractSource> children, VerificationKeySet keys) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(children, "children");
        VerifiedContract verifiedRoot = VerifiedContracts.load(List.of(root), keys).all().getFirst();
        VerifiedContracts verifiedChildren = VerifiedContracts.load(children, keys);
        Map<URI, CompositionContract> byId = verifiedChildren.all().stream()
                .collect(Collectors.toMap(child -> child.contract().contractId(), VerifiedContract::contract, (a, b) -> a));
        Set<URI> closure = closure(verifiedRoot.contract(), byId::get);
        Set<String> missing = new TreeSet<>();
        closure.stream().filter(id -> !byId.containsKey(id)).forEach(id -> missing.add(id.toString()));
        Set<String> extra = new TreeSet<>();
        byId.keySet().stream().filter(id -> !closure.contains(id)).forEach(id -> extra.add(id.toString()));
        if (byId.containsKey(verifiedRoot.contract().contractId())) {
            extra.add(verifiedRoot.contract().contractId() + " (the root itself)");
        }
        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) problems.add("the signed composition names children that are missing: " + String.join(", ", missing));
        if (!extra.isEmpty()) problems.add("contracts that are not part of the signed composition: " + String.join(", ", extra));
        if (!problems.isEmpty()) {
            throw new ComponentHostException(HostFailureCode.CONTRACT_UNVERIFIED, root.name(), String.join("; ", problems));
        }
        return new VerifiedComposition(verifiedRoot, verifiedChildren);
    }

    /**
     * Reads the root file and every {@code *.json} regular file directly in the child directory (non-recursive), then
     * {@link #load}s them.
     *
     * @throws ComponentHostException {@link HostFailureCode#CONTRACT_UNVERIFIED} when the root file or the child directory
     *                                is missing or unreadable, or as {@link #load}
     */
    public static VerifiedComposition loadDirectory(Path rootFile, Path childDirectory, VerificationKeySet keys) {
        Objects.requireNonNull(rootFile, "rootFile");
        Objects.requireNonNull(childDirectory, "childDirectory");
        if (!Files.isRegularFile(rootFile)) {
            throw new ComponentHostException(HostFailureCode.CONTRACT_UNVERIFIED, rootFile.toString(),
                    "the signed composition root is missing");
        }
        if (!Files.isDirectory(childDirectory)) {
            throw new ComponentHostException(HostFailureCode.CONTRACT_UNVERIFIED, childDirectory.toString(),
                    "frozen contract directory is missing");
        }
        try {
            ContractSource root = new ContractSource(rootFile.getFileName().toString(), Files.readAllBytes(rootFile));
            List<ContractSource> children = new ArrayList<>();
            try (Stream<Path> files = Files.list(childDirectory)) {
                for (Path file : files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                        .sorted().toList()) {
                    children.add(new ContractSource(file.getFileName().toString(), Files.readAllBytes(file)));
                }
            }
            return load(root, children, keys);
        } catch (IOException failure) {
            throw new ComponentHostException(HostFailureCode.CONTRACT_UNVERIFIED, childDirectory.toString(),
                    "the signed composition is unreadable", failure);
        }
    }

    /** The verified aggregate root. */
    public VerifiedContract root() {
        return root;
    }

    /** The verified children: exactly the root's closure. */
    public VerifiedContracts children() {
        return children;
    }

    /** The children whose binding is for {@code providerCapability}, ordered as {@link #children()}. */
    public List<VerifiedContract> childrenFor(String providerCapability) {
        return children.all().stream()
                .filter(child -> child.contract().binding() != null
                        && Objects.equals(providerCapability, child.contract().binding().providerCapability()))
                .toList();
    }

    /** The transitive child ids of {@code root}, following children that are present; cycles end the walk. */
    private static Set<URI> closure(CompositionContract root, Function<URI, CompositionContract> lookup) {
        Set<URI> closure = new LinkedHashSet<>();
        List<URI> pending = new ArrayList<>(childIds(root));
        while (!pending.isEmpty()) {
            URI id = pending.removeFirst();
            if (!closure.add(id)) continue;
            CompositionContract child = lookup.apply(id);
            if (child != null) pending.addAll(childIds(child));
        }
        return closure;
    }

    private static List<URI> childIds(CompositionContract contract) {
        return contract.metadata() == null ? List.of() : contract.metadata().childContractIds();
    }
}
