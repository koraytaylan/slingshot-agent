// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Arbitrary bytes offered as any command's arguments, with the commands read from the registry.
 *
 * <p>Sixty-four argument shapes is sixty-four places a hostile or merely confused value arrives.
 * Deriving them from the registry directory rather than listing them is what makes the
 * sixty-fifth command fuzzed on the day it lands — a list here would be a list somebody edits on
 * the day they are thinking about the command rather than about this.</p>
 *
 * <p>Three properties. An input either reads as a document or is refused naming a bound; nothing
 * reads as a value past a bound the contract states, however the input is shaped; and no address
 * inside one survives as something outside the roots a command declares — including through the
 * separator, parent-reference and encoding tricks, which is what most of the corpus is.</p>
 */
public final class CommandArgumentTarget implements FuzzTarget {

    /** How the fuzzing tool reaches this target. */
    private static final CommandArgumentTarget TARGET = new CommandArgumentTarget();

    /** The member an address arrives in, which is the one an escape would be spelled into. */
    private static final List<String> ADDRESS_MEMBERS =
            List.of("path", "source_path", "destination_path", "root_path", "parent_path");

    private final AgentContract contract;
    private final BoundedDocumentReader.Bounds bounds;
    private final List<RegistryRow> rows;

    /** Holds one target over the registry this repository commits. */
    public CommandArgumentTarget() {
        this.contract = ((AgentContract.Loaded) AgentContract.load()).contract();
        this.bounds = BoundedDocumentReader.Bounds.from(contract);
        this.rows = rowsFrom(Path.of(System.getProperty("slingshot.repository.root", "."))
                .resolve(CommandRegistry.REGISTRY_DIRECTORY));
    }

    /**
     * The entry point the fuzzing tool calls.
     *
     * @param input arbitrary bytes
     */
    public static void fuzzerTestOneInput(byte[] input) {
        final FuzzOutcome outcome = TARGET.of(input);
        if (outcome instanceof final FuzzOutcome.Broken broken) {
            throw new AssertionError(broken.property() + ": " + broken.detail());
        }
    }

    /**
     * Every command the registry declares, which is what makes this target derived.
     *
     * @return the rows, in wire order
     */
    public List<RegistryRow> rows() {
        return List.copyOf(rows);
    }

    @Override
    public FuzzOutcome of(byte[] input) {
        final Attempted.Answered<BoundedDocumentReader.Outcome> asked =
                Attempted.of(() -> BoundedDocumentReader.read(input, bounds));
        if (asked.threw()) {
            return FuzzOutcome.broken("reading arguments answers rather than throws",
                    "it threw " + asked.threwWhat());
        }
        final BoundedDocumentReader.Outcome read = asked.value().orElseThrow();
        if (!(read instanceof final BoundedDocumentReader.Read held)) {
            return FuzzOutcome.held();
        }
        if (!(held.value() instanceof final DocumentValue.Mapping mapping)) {
            return FuzzOutcome.held();
        }
        final FuzzOutcome addressed = addresses(mapping);
        return addressed instanceof FuzzOutcome.Broken ? addressed : bounded(mapping);
    }

    /**
     * That no address read out of one document is one a command would act on relative to nothing.
     *
     * <p>Every command that takes an address takes an absolute one, and the caller's own session
     * decides what they may reach at it. So the property is not that an address cannot climb — a
     * climb resolves to another absolute address the caller's own permissions still govern — it is
     * that nothing a caller can spell produces an address that is not absolute once it is read.
     * A relative address is the one shape where what it means depends on where the reader happened
     * to be, and that is a decision no caller should be making from a wire.</p>
     *
     * @param mapping what was read
     * @return whether the property held
     */
    private static FuzzOutcome addresses(DocumentValue.Mapping mapping) {
        for (final String member : ADDRESS_MEMBERS) {
            if (!(mapping.members().get(member) instanceof final DocumentValue.Text address)
                    || !address.value().startsWith("/")) {
                continue;
            }
            final String normalised = Path.of(address.value()).normalize().toString();
            if (!normalised.startsWith("/")) {
                return FuzzOutcome.broken("no address read as absolute stops being absolute",
                        member + " read as " + address.value() + ", which normalises to "
                                + normalised);
            }
        }
        return FuzzOutcome.held();
    }

    /**
     * That no value read out of one document is past a bound the contract states.
     *
     * @param mapping what was read
     * @return whether the property held
     */
    private FuzzOutcome bounded(DocumentValue.Mapping mapping) {
        final long stringBound = contract.value(
                rs.slingshot.agent.contract.ContractLimit.MAXIMUM_DOCUMENT_STRING_BYTES);
        return mapping.members().values().stream()
                .filter(DocumentValue.Text.class::isInstance)
                .map(DocumentValue.Text.class::cast)
                .anyMatch(text -> text.value().getBytes(StandardCharsets.UTF_8).length
                        > stringBound)
                ? FuzzOutcome.broken("nothing reads as a value past a bound the contract states",
                        "a string longer than " + stringBound + " bytes was read")
                : FuzzOutcome.held();
    }

    private static List<RegistryRow> rowsFrom(Path directory) {
        return CommandRegistry.read(directory) instanceof final CommandRegistry.Loaded loaded
                ? loaded.registry().rows() : List.of();
    }
}
