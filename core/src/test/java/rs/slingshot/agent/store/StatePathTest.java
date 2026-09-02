// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * Where a record lives, derived from what it is called and from nothing else.
 *
 * <p>The bucket is checked against a corpus rather than argued about: sixteen thousand identifiers
 * are derived, and no node is allowed to hold more children than the layout says a node may. A
 * derivation that looked even and was not would be found here rather than on the day a customer's
 * author slows down.</p>
 */
final class StatePathTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path INITIALISATION = REPOSITORY.resolve("ui.config/src/main/content/"
            + "jcr_root/apps/slingshot-agent/osgiconfig/config/"
            + "org.apache.sling.jcr.repoinit.RepositoryInitializer~slingshot-agent.cfg.json");

    /** How many identifiers the bucket is checked against. */
    private static final int CORPUS = 16384;

    @Test
    @DisplayName("one identifier derives one path, every time, and two derive two")
    void theDerivationIsDeterministicAndCollisionFree() {
        final AgentOperationIdentifier first = identifier("one operation");
        final AgentOperationIdentifier second = identifier("another operation");
        assertEquals(StatePath.operation(generation(), first).path(),
                StatePath.operation(generation(), first).path());
        assertFalse(StatePath.operation(generation(), first).path()
                        .equals(StatePath.operation(generation(), second).path()),
                "two operations derived one path");
        assertTrue(StatePath.operation(generation(), first).path()
                        .startsWith(StatePath.ROOT + "/" + StatePath.OPERATIONS + "/g1/"),
                StatePath.operation(generation(), first).path());
        assertTrue(StatePath.operation(generation(), first).path()
                        .endsWith("/" + first.rendered()),
                "the path does not end in the identifier a recovering caller holds");
    }

    @Test
    @DisplayName("a record moves with the incarnation of the store it belongs to")
    void thePathCarriesTheGeneration() {
        final AgentOperationIdentifier identifier = identifier("one operation");
        assertFalse(StatePath.operation(generation(), identifier).path()
                        .equals(StatePath.operation(laterGeneration(), identifier).path()),
                "an operation from another incarnation shares its predecessor's path");
    }

    @Test
    @DisplayName("no bucket holds more children than the layout says a node may")
    void theBucketKeepsEveryNodeUnderTheCeiling() {
        final Map<String, Integer> occupancy = new HashMap<>();
        for (int index = 0; index < CORPUS; index++) {
            final String path = StatePath.operation(generation(), identifier("operation " + index))
                    .path();
            occupancy.merge(path.substring(0, path.lastIndexOf('/')), 1, Integer::sum);
        }
        final long ceiling = layout().childCeiling();
        final int widest = occupancy.values().stream().mapToInt(Integer::intValue).max()
                .orElseThrow();
        assertTrue(widest <= ceiling,
                "a bucket holds " + widest + " children, past the ceiling of " + ceiling);
        assertTrue(occupancy.size() > CORPUS / ceiling,
                "the corpus landed in " + occupancy.size() + " buckets, which is not spread");
    }

    @Test
    @DisplayName("a name that would escape the tree is refused before a path is built")
    void everyEscapeIsRefusedDistinctly() {
        assertEquals(StatePath.Refusal.BEGINS_WITH_A_SEPARATOR, refusalOf("/etc/passwd"));
        assertEquals(StatePath.Refusal.CLIMBS_OUT_OF_THE_TREE, refusalOf("..-and-out"));
        assertEquals(StatePath.Refusal.CARRIES_A_SEPARATOR, refusalOf("content-somewhere/else"));
        assertEquals(StatePath.Refusal.TOO_SHORT, refusalOf("ab"));
        assertEquals(StatePath.Refusal.NOT_A_NAME, refusalOf("a name with spaces"));
    }

    @Test
    @DisplayName("a caller's counters are bucketed by the same derivation an operation is")
    void aCallerIsBucketedTheSameWay() {
        final StatePath.Caller caller = caller("a-submitting-caller");
        final String path = StatePath.caller(caller).path();
        assertTrue(path.startsWith(StatePath.ROOT + "/" + StatePath.CAPACITY + "/"
                + StatePath.CALLERS + "/"), path);
        assertEquals(StatePath.BUCKET_DEPTH + 1,
                path.substring(path.indexOf(StatePath.CALLERS)).split("/").length - 1,
                "a caller is bucketed to another depth than an operation");
        assertEquals(StatePath.caller(caller).path(), StatePath.caller(caller).path());
        assertEquals(caller, caller("a-submitting-caller"),
                "two constraints of one name are two callers");
        assertEquals(caller.hashCode(), caller("a-submitting-caller").hashCode());
        assertEquals("a-submitting-caller", caller.toString());
        assertEquals("a-submitting-caller", caller.name());
        assertNotEquals(caller, caller("another-caller"),
                "two names were constrained into one caller");
    }

    @Test
    @DisplayName("a layout that is not one is refused rather than read as an empty tree")
    void aDocumentThatIsNotALayoutIsRefused() {
        assertInstanceOf(StateLayout.Refused.class, StateLayout.read("# nothing declared here"),
                "a document declaring no tree was read as a layout");
        assertTrue(assertInstanceOf(StateLayout.Refused.class,
                        StateLayout.read("[tree]\nroot = \"/var/slingshot-agent\"")).detail()
                        .contains("no root or no node"),
                "a layout with a root and no node was read as a layout");
    }

    @Test
    @DisplayName("nothing derives a path from a string")
    void noPathComesFromARawString() {
        final List<Method> derivations = Arrays.stream(StatePath.class.getMethods())
                .filter(method -> method.getReturnType().equals(StatePath.class))
                .filter(method -> java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                .toList();
        assertEquals(3, derivations.size(), "a derivation was added or lost");
        assertTrue(derivations.stream()
                        .filter(method -> !"deployment".equals(method.getName()))
                        .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                        .noneMatch(String.class::equals),
                "a record's path can be derived from a string a request supplied");
    }

    @Test
    @DisplayName("the layout this bundle carries and the tree the script creates agree both ways")
    void theLayoutAndTheInitialisationAgree() {
        final String script = read(INITIALISATION);
        final Set<String> created = script.lines()
                .filter(line -> line.contains("create path"))
                .map(line -> line.substring(line.indexOf(StatePath.ROOT)).replace("\",", "")
                        .replace("\"", "").strip())
                .collect(java.util.stream.Collectors.toSet());
        final Set<String> declared = layout().nodes().stream()
                .filter(node -> node.primitive() == StateLayout.Primitive.INITIALISATION)
                .map(node -> StatePath.ROOT + "/" + node.path())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(created.contains(StatePath.ROOT), "the script does not create the agent's tree");
        assertEquals(declared, created.stream()
                        .filter(path -> !StatePath.ROOT.equals(path))
                        .collect(java.util.stream.Collectors.toSet()),
                "the layout and the initialisation script disagree about the tree");
    }

    @Test
    @DisplayName("the layout declares every node this bundle writes, and no node it does not")
    void theLayoutAndTheDerivationAgree() {
        final StateLayout layout = layout();
        assertEquals(StatePath.ROOT, layout.root());
        assertEquals(StatePath.BUCKET_DEPTH, layout.bucketDepth());
        assertEquals(StatePath.BUCKET_CHARACTERS, layout.bucketCharacters());
        StatePath.operationChildren().forEach(child -> assertTrue(layout.node(child).isPresent(),
                child + " is written and the layout does not declare it"));
        assertTrue(layout.node("operation").isPresent());
        assertEquals(StateLayout.Primitive.CLAIM, layout.node("operation").orElseThrow()
                .primitive(), "an operation is claimed by creating it, and the layout says"
                + " otherwise");
    }

    private static StateLayout layout() {
        return assertInstanceOf(StateLayout.Loaded.class, StateLayout.load(),
                "the layout is not embedded in this bundle").layout();
    }

    private static StatePath.Refusal refusalOf(String name) {
        return assertInstanceOf(StatePath.Refused.class, StatePath.caller(name),
                name + " was constrained into a caller").refusal();
    }

    private static StatePath.Caller caller(String name) {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller(name),
                name + " was refused").caller();
    }

    private static AgentOperationIdentifier identifier(String seed) {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(Digest.of(seed.getBytes(StandardCharsets.UTF_8))
                        .rendered(), contract()), "the identifier was refused").identifier();
    }

    private static EventStoreGeneration generation() {
        return generationOf(EventStoreGeneration.FIRST);
    }

    private static EventStoreGeneration laterGeneration() {
        return generationOf(EventStoreGeneration.FIRST + 1);
    }

    private static EventStoreGeneration generationOf(long number) {
        return assertInstanceOf(EventStoreGeneration.Held.class, EventStoreGeneration.of(number),
                number + " is not a generation").generation();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
