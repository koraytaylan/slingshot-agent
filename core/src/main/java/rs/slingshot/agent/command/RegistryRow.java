// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One command, declared in a file of its own.
 *
 * <p>Everything a command may do is here rather than in the command: the result bound it answers
 * under, the failure categories it may report, whether it needs an operation key, how much room it
 * may take inside this agent's own tree while it works, and where it runs. A handler that decided
 * any of those for itself would be a handler that could differ from the fortieth one.</p>
 *
 * <p>The staging budget and the execution class are this side's own and are not compared against
 * the client's table: both are facts about how this agent executes a command rather than about the
 * contract the two halves share.</p>
 *
 * @param wireName what a caller submits it as
 * @param contractVersion the semantic contract version this row is for
 * @param accessClass whether it changes anything
 * @param operationKey whether a caller has to supply one
 * @param resultBytes the most its result may hold before it overflows into an artifact
 * @param failureCategories every way it may fail, and there is no other
 * @param argumentDigest the digest of the schema its arguments are held to
 * @param resultDigest the digest of the schema its result is held to
 * @param limitsDigest the digest of the contract limits it is declared against
 * @param stagingBytes how much room it may take inside this agent's own tree, which is none for
 *     every command but one
 * @param executionClass where it runs, which decides whose identity it runs under
 */
public record RegistryRow(String wireName, String contractVersion, AccessClass accessClass,
                          OperationKey operationKey, long resultBytes,
                          List<String> failureCategories, String argumentDigest,
                          String resultDigest, String limitsDigest, long stagingBytes,
                          ExecutionClass executionClass) {

    /** Whether a caller supplies an operation key, which follows from intrinsic idempotency. */
    public enum OperationKey {
        /** Running it twice is running it once, so a key would decide nothing. */
        REFUSED("refused"),
        /** It is not intrinsically idempotent, so the caller supplies one. */
        REQUIRED("required");

        private final String spelling;

        OperationKey(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How this requirement is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The requirement one spelling names.
         *
         * @param spelling the spelling
         * @return the requirement, or nothing where this build knows none spelled that way
         */
        public static Optional<OperationKey> named(String spelling) {
            return java.util.Arrays.stream(values())
                    .filter(held -> held.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /**
     * Holds a row whose every member is stated and whose members agree with one another.
     *
     * @throws IllegalArgumentException if a member is missing, or if the access class and the key
     *     requirement disagree
     */
    public RegistryRow {
        requireStated(wireName, "wire name");
        requireStated(contractVersion, "contract version");
        requireStated(argumentDigest, "argument schema digest");
        requireStated(resultDigest, "result schema digest");
        requireStated(limitsDigest, "contract limits digest");
        if (failureCategories.isEmpty()) {
            throw new IllegalArgumentException(wireName + " declares no way of failing, and a"
                    + " command that cannot fail is one whose failures a caller cannot name");
        }
        if (resultBytes <= 0) {
            throw new IllegalArgumentException(wireName + " declares no result bound");
        }
        if (stagingBytes < 0) {
            throw new IllegalArgumentException(wireName + " declares a staging budget below none");
        }
        failureCategories = List.copyOf(failureCategories);
    }

    /**
     * Whether this command works inside this agent's own tree while it runs.
     *
     * <p>The budget is what decides it rather than the command's name: a row declaring room gets a
     * staging area and a row declaring none does not, whatever either is called.</p>
     *
     * @return whether it needs room
     */
    public Staging staging() {
        return stagingBytes > 0 ? Staging.INSIDE_THE_AGENTS_OWN_TREE : Staging.NONE_AT_ALL;
    }

    /** Whether a command works somewhere while it runs. */
    public enum Staging {
        /** It has room of its own inside the agent's tree, bounded by what its row declares. */
        INSIDE_THE_AGENTS_OWN_TREE,
        /** It has none, which is every command but one. */
        NONE_AT_ALL
    }

    /**
     * The five fields that say exactly which contract this row is, derived from the row itself.
     *
     * <p>Derived rather than assembled from anything a caller supplied: an identity built out of a
     * request is an identity a request can choose. It is read through the same reader a submission
     * is read through, so a row whose digest is not a digest is refused here rather than believed
     * and compared later.</p>
     *
     * @param bounds the two length bounds an identity is read under
     * @return the identity, or the one reason this row does not have one
     */
    public CommandContractIdentity.Outcome identity(CommandContractIdentity.Bounds bounds) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(CommandContractIdentity.WIRE_NAME, new DocumentValue.Text(wireName));
        members.put(CommandContractIdentity.CONTRACT_VERSION,
                new DocumentValue.Text(contractVersion));
        members.put(CommandContractIdentity.LIMITS_DIGEST, new DocumentValue.Text(limitsDigest));
        members.put(CommandContractIdentity.ARGUMENT_DIGEST,
                new DocumentValue.Text(argumentDigest));
        members.put(CommandContractIdentity.RESULT_DIGEST, new DocumentValue.Text(resultDigest));
        return CommandContractIdentity.of(new DocumentValue.Mapping(members), bounds);
    }

    /**
     * The one way this row disagrees with itself, where it does.
     *
     * <p>The client's own rule: a command that is not intrinsically idempotent requires a key, and
     * one that is refuses it. A row that says otherwise is a row whose two halves were written by
     * people who did not read each other.</p>
     *
     * @return what is wrong with it, or nothing where the two agree
     */
    public Optional<String> disagreement() {
        // Nothing about a row's access class decides its key requirement, so nothing here compares
        // them. This once refused a read that required a key, on the reasoning that a read is
        // intrinsically idempotent - which is not true and is not what the client says. The client
        // classifies the two independently and publishes seven reads that are intrinsically
        // idempotent beside two that are not: reading the repository twice is not one operation
        // when the repository can change between the reads, so `load_content_as_json` and
        // `download_content_package` take a key and the seven discovery reads refuse one.
        //
        // A row carries its key requirement and does not carry its idempotency, so there is no
        // second fact here to check the first against. The check that can be made is against the
        // client's own published classification, which is the authority for both - and the
        // conformance gate makes it, where the client's table already is. Re-deriving it here
        // would be this side inventing an answer to a question the other side has already
        // answered, which is exactly how the two halves came to disagree the first time.
        return Optional.empty();
    }

    private static void requireStated(String value, String part) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("a registry row states no " + part);
        }
    }
}
