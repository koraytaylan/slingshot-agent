// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Two instances against one shared repository.
 *
 * <p>This is not a later refinement of the single-instance harness. An Adobe Experience Manager as
 * a Cloud Service author <em>is</em> a cluster, and every property this product has about
 * contention — a lease two workers race for, a count two nodes advance, a key ring two nodes rotate
 * — is a property a single instance cannot exhibit at all. A harness that could only start one
 * would let every one of those pass here and fail on a customer's author, so both arrangements
 * exist from the first commit and the suites that need the second say so.</p>
 *
 * <p>Each node is addressed separately, so a suite can act on one and observe the other. Cleanup
 * goes through the retained handles — both nodes and the store — and never through a name.</p>
 */
public final class ClusterHarness {

    /** The port the shared document store answers on inside its own container. */
    private static final int DOCUMENT_STORE_PORT = 27017;

    /** What both nodes reach the shared document store by, on the network they share with it. */
    public static final String STORE_ALIAS = "document-store";

    private final ContainerHarness harness;

    /**
     * Holds the wrapper both nodes and the store are started through.
     *
     * @param harness the container harness
     */
    public ClusterHarness(ContainerHarness harness) {
        this.harness = harness;
    }

    /**
     * Reads the harness's own values and builds a cluster harness on them.
     *
     * @param root the repository root
     * @return the cluster harness
     */
    public static ClusterHarness at(Path root) {
        // A cluster starts published runtimes and a document store, not a quickstart, so it is
        // held to what those take. The five-minute ceiling is for the tier nothing here starts.
        return new ClusterHarness(ContainerHarness.at(root).forClusteredRuntime());
    }

    /**
     * Two nodes sharing one repository, each separately addressable.
     *
     * @param store the shared document store
     * @param first the first node
     * @param second the second node
     * @param network the network the three of them reach one another on
     */
    public record Cluster(ContainerHandle store, ContainerHandle first, ContainerHandle second,
                          String network) {
    }

    /** The result of starting one: a cluster, or the one reason there is none. */
    public sealed interface Outcome permits Started, Refused {
    }

    /**
     * A cluster whose store and both nodes are ready.
     *
     * @param cluster the cluster
     */
    public record Started(Cluster cluster) implements Outcome {
    }

    /**
     * A start that produced no cluster, with everything it did start already stopped.
     *
     * @param failure why there is none
     * @param detail what was observed
     */
    public record Refused(ContainerHarness.Failure failure, String detail) implements Outcome {
    }

    /**
     * Starts one shared document store and two nodes against it.
     *
     * @param storeImage the pinned document store image
     * @param nodeImage the pinned node image
     * @param nodePort the port a node answers on inside its own container
     * @param ready what makes a node ready
     * @return the cluster, or the one reason there is none
     */
    public Outcome start(String storeImage, String nodeImage, int nodePort,
                         Predicate<ContainerHandle> ready) {
        return start(storeImage, nodeImage, nodePort, List.of(), ready);
    }

    /**
     * Starts one shared document store and two nodes running a command of the caller's own.
     *
     * @param storeImage the pinned document store image
     * @param nodeImage the pinned node image
     * @param nodePort the port a node answers on inside its own container
     * @param nodeCommand what to run inside each node, empty for the image's own entry point
     * @param ready what makes a node ready
     * @return the cluster, or the one reason there is none
     */
    public Outcome start(String storeImage, String nodeImage, int nodePort,
                         List<String> nodeCommand, Predicate<ContainerHandle> ready) {
        return start(storeImage, nodeImage, nodePort, nodeCommand, List.of(), ready);
    }

    /**
     * Starts one shared document store and two nodes that reach it by name on a network of their
     * own.
     *
     * <p>The store is reached over that network rather than through a port published on the host: a
     * store published on the host would be a store anything on the machine could reach, and the
     * harness's claim to expose nothing but the ports a tier declares would stop being true. The
     * nodes' own ports are still published on the loopback address, because the suite is on the
     * host and has to ask them things.</p>
     *
     * @param storeImage the pinned document store image
     * @param nodeImage the pinned node image
     * @param nodePort the port a node answers on inside its own container
     * @param nodeCommand what to run inside each node, empty for the image's own entry point
     * @param nodeEnvironment values to set inside each node, as {@code NAME=value}, beside the ones
     *     that say where the shared store is
     * @param ready what makes a node ready
     * @return the cluster, or the one reason there is none
     */
    public Outcome start(String storeImage, String nodeImage, int nodePort,
                         List<String> nodeCommand, List<String> nodeEnvironment,
                         Predicate<ContainerHandle> ready) {
        final String network = "slingshot-agent-interop-" + java.util.UUID.randomUUID();
        harness.createNetwork(network);
        final ContainerHarness.Outcome store = harness.start(storeImage, DOCUMENT_STORE_PORT,
                List.of(), List.of(), new ContainerHarness.Attachment(network, STORE_ALIAS),
                this::accepting);
        if (store instanceof final ContainerHarness.Refused refused) {
            harness.removeNetwork(network);
            return new Refused(refused.failure(), "the shared document store: " + refused.detail());
        }
        final ContainerHandle storeHandle = ((ContainerHarness.Started) store).handle();
        final List<String> environment = new java.util.ArrayList<>(nodeEnvironment);
        environment.add("SLINGSHOT_DOCUMENT_STORE=" + STORE_ALIAS + ":" + DOCUMENT_STORE_PORT);
        environment.add("MONGODB_HOST=" + STORE_ALIAS);
        environment.add("MONGODB_PORT=" + DOCUMENT_STORE_PORT);
        return nodes(nodeImage, nodePort, nodeCommand, environment, network, storeHandle, ready);
    }

    /** What the document store writes once it is listening for connections. */
    private static final String STORE_IS_LISTENING = "Waiting for connections";

    /**
     * Whether the document store is listening yet, asked of the store itself.
     *
     * <p>Created and listening are not the same moment, and this waited for the first while meaning
     * the second. A node brought up against a store that is still opening its own files cannot
     * reach the repository it is supposed to share, never finishes starting, and is reported as a
     * node that would not start - so the store's race was read as the node's failure, on whichever
     * node happened to lose it.</p>
     *
     * @param handle the store's container
     * @return whether it has said it is waiting for connections
     */
    private boolean accepting(ContainerHandle handle) {
        return harness.capturedOutput(handle).contains(STORE_IS_LISTENING);
    }

    private Outcome nodes(String nodeImage, int nodePort, List<String> nodeCommand,
                          List<String> environment, String network, ContainerHandle storeHandle,
                          Predicate<ContainerHandle> ready) {
        final ContainerHarness.Attachment attachment =
                new ContainerHarness.Attachment(network, "node");
        final ContainerHarness.Outcome first = harness.start(nodeImage, nodePort, environment,
                nodeCommand, attachment, ready);
        if (first instanceof final ContainerHarness.Refused refused) {
            harness.stop(storeHandle);
            harness.removeNetwork(network);
            return new Refused(refused.failure(), "the first node: " + refused.detail());
        }
        final ContainerHandle firstHandle = ((ContainerHarness.Started) first).handle();
        final ContainerHarness.Outcome second = harness.start(nodeImage, nodePort, environment,
                nodeCommand, attachment, ready);
        if (second instanceof final ContainerHarness.Refused refused) {
            harness.stop(firstHandle);
            harness.stop(storeHandle);
            harness.removeNetwork(network);
            return new Refused(refused.failure(), "the second node: " + refused.detail());
        }
        return new Started(new Cluster(storeHandle, firstHandle,
                ((ContainerHarness.Started) second).handle(), network));
    }

    /**
     * Stops both nodes and the store, through the handles that started them.
     *
     * @param cluster the cluster to stop
     */
    public void stop(Cluster cluster) {
        harness.stop(cluster.first());
        harness.stop(cluster.second());
        harness.stop(cluster.store());
        harness.removeNetwork(cluster.network());
    }

    /**
     * The wrapper both nodes and the store were started through.
     *
     * @return the container harness
     */
    public ContainerHarness harness() {
        return harness;
    }
}
