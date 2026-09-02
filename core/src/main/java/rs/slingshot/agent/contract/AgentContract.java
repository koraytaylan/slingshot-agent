// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;

/**
 * The one typed accessor for every bound this agent is held to.
 *
 * <p>The contract's bytes and their digest are embedded in this bundle as resources rather than
 * copied into Java, and the bytes are authenticated against the digest <em>before</em> a single
 * bound is parsed. A contract nobody can authenticate is a contract nobody may read from, so a
 * digest mismatch answers a refusal and never a partly-populated contract.</p>
 *
 * <p>Every bound is reached by naming a {@link ContractLimit}. There is no lookup by string and no
 * second place a bound is written down: a constant named after one of these limits, anywhere else
 * in this repository, is a second declaration that can disagree with this one quietly, and the
 * source policy refuses it.</p>
 */
public final class AgentContract {

    /** Where the contract's bytes are embedded in this bundle. */
    public static final String CONTRACT_RESOURCE = "/rs/slingshot/agent/contract/agent-contract.toml";

    /** Where the digest authenticating those bytes is embedded. */
    public static final String DIGEST_RESOURCE = "/rs/slingshot/agent/contract/agent-contract.sha256";

    /**
     * Where the client's own transport contract digest is embedded.
     *
     * <p>It is the client's value, reproduced rather than recomputed. Recomputing it here would
     * mean digesting this side's copy of the bounds, which is a different document with the same
     * numbers in it — and a client comparing the two would then see two agents that speak the same
     * protocol disagree about which protocol that is.</p>
     */
    public static final String TRANSPORT_DIGEST_RESOURCE =
            "/rs/slingshot/agent/contract/transport-contract.sha256";

    /**
     * Where the client's own command contract digest is embedded.
     *
     * <p>The client's value, reproduced for the same reason the transport one is: it names the
     * document the client digested, not this side's rendering of the same numbers.</p>
     */
    public static final String COMMAND_DIGEST_RESOURCE =
            "/rs/slingshot/agent/contract/command-contract.sha256";

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final char TABLE_OPEN = '[';

    private static final char TABLE_CLOSE = ']';

    private static final char COMMENT = '#';

    private static final String ASSIGNMENT = "=";

    private final Map<ContractLimit, Long> values;

    private AgentContract(Map<ContractLimit, Long> values) {
        this.values = values;
    }

    /** Why a contract was refused. Each cause is distinct because each has a different fix. */
    public enum Failure {
        /** The embedded contract or its digest is not there to read. */
        UNREADABLE,
        /** The bytes do not match the digest committed beside them. */
        DIGEST_MISMATCH,
        /** The bytes are not a contract document at all. */
        UNPARSABLE,
        /** A bound this build names is absent from the document. */
        MISSING_BOUND,
        /** The document declares a bound this build does not name. */
        UNKNOWN_BOUND,
        /** A declared bound carries something that is not a whole number. */
        WRONG_TYPE,
        /** Two bounds the document declares cannot both be true. */
        INCONSISTENT_BOUND
    }

    /** The result of loading: a whole contract, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A contract whose bytes were authenticated and whose every bound was read.
     *
     * @param contract the loaded contract
     */
    public record Loaded(AgentContract contract) implements Outcome {
    }

    /**
     * A load that produced no contract at all.
     *
     * @param failure why the contract was refused
     * @param detail what was refused, named so that somebody can fix it
     */
    public record Refused(Failure failure, String detail) implements Outcome {
    }

    /**
     * Loads the contract embedded in this bundle.
     *
     * @return the contract, or the one reason it was refused
     */
    public static Outcome load() {
        final Optional<byte[]> document = resource(CONTRACT_RESOURCE);
        final Optional<byte[]> digest = resource(DIGEST_RESOURCE);
        if (document.isEmpty() || digest.isEmpty()) {
            return new Refused(Failure.UNREADABLE,
                    "the contract or its digest is not embedded in this bundle");
        }
        return load(document.get(), new String(digest.get(), StandardCharsets.UTF_8));
    }

    /**
     * Authenticates bytes against a digest and reads the contract out of them.
     *
     * @param document the contract's bytes
     * @param declaredDigest the digest committed beside them, in lower-case hexadecimal
     * @return the contract, or the one reason it was refused
     */
    public static Outcome load(byte[] document, String declaredDigest) {
        final String actual = digestOf(document);
        final String expected = declaredDigest.strip();
        if (!actual.equals(expected)) {
            return new Refused(Failure.DIGEST_MISMATCH,
                    "the contract's bytes digest to " + actual + " and not to " + expected);
        }
        return read(new String(document, StandardCharsets.UTF_8));
    }

    /**
     * The digest these bytes carry, in the form the committed digest file uses.
     *
     * @param document the bytes to digest
     * @return the lower-case hexadecimal digest
     */
    public static String digestOf(byte[] document) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST_ALGORITHM).digest(document));
        } catch (final NoSuchAlgorithmException absent) {
            throw new IllegalStateException("this runtime provides no " + DIGEST_ALGORITHM, absent);
        }
    }

    /**
     * The command contract this agent is held to, as the client's own repository digests it.
     *
     * @return the digest, in lower-case hexadecimal
     * @throws IllegalStateException if it is not embedded in this bundle, because an agent that
     *     cannot say which bounds it enforces must not advertise that it can be used
     */
    public static String commandContractLimitsDigest() {
        return resource(COMMAND_DIGEST_RESOURCE)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8).strip())
                .orElseThrow(() -> new IllegalStateException(
                        "the command contract digest is not embedded in this bundle"));
    }

    /**
     * The transport contract this agent speaks, as the client's own repository digests it.
     *
     * @return the digest, in lower-case hexadecimal
     * @throws IllegalStateException if it is not embedded in this bundle, because an agent that
     *     cannot say which protocol it speaks must not advertise that it can be used
     */
    public static String transportContractDigest() {
        return resource(TRANSPORT_DIGEST_RESOURCE)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8).strip())
                .orElseThrow(() -> new IllegalStateException(
                        "the transport contract digest is not embedded in this bundle"));
    }

    /**
     * The value of one bound.
     *
     * @param limit the bound to read
     * @return the value the contract declares for it
     */
    public long value(ContractLimit limit) {
        return values.get(limit);
    }

    /**
     * Every bound this contract carries.
     *
     * @return the bounds, in the order they are declared
     */
    public SequencedSet<ContractLimit> limits() {
        return new LinkedHashSet<>(values.keySet());
    }

    private static Outcome read(String text) {
        final Map<String, Long> declared = new LinkedHashMap<>();
        String table = "";
        int number = 0;
        for (final String raw : text.lines().toList()) {
            number++;
            final String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == TABLE_OPEN) {
                if (line.charAt(line.length() - 1) != TABLE_CLOSE) {
                    return new Refused(Failure.UNPARSABLE, "line " + number + " is not a table header");
                }
                table = line.substring(1, line.length() - 1).strip();
                continue;
            }
            final int assignment = line.indexOf(ASSIGNMENT);
            if (assignment < 1 || table.isEmpty()) {
                return new Refused(Failure.UNPARSABLE,
                        "line " + number + " is neither a table header nor an assignment in a table");
            }
            final String key = table + "." + line.substring(0, assignment).strip();
            final String value = line.substring(assignment + 1).strip();
            final Optional<Long> parsed = wholeNumber(value);
            if (parsed.isEmpty()) {
                return new Refused(Failure.WRONG_TYPE, key + " carries " + value
                        + ", which is not a whole number");
            }
            if (declared.put(key, parsed.get()) != null) {
                return new Refused(Failure.UNPARSABLE, key + " is declared twice");
            }
        }
        return bind(declared);
    }

    private static Outcome bind(Map<String, Long> declared) {
        final Map<ContractLimit, Long> values = new EnumMap<>(ContractLimit.class);
        for (final ContractLimit limit : ContractLimit.values()) {
            final Long value = declared.get(limit.path());
            if (value == null) {
                return new Refused(Failure.MISSING_BOUND, limit.path());
            }
            values.put(limit, value);
        }
        final Optional<String> unknown = declared.keySet().stream()
                .filter(path -> List.of(ContractLimit.values()).stream()
                        .noneMatch(limit -> limit.path().equals(path)))
                .sorted()
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Failure.UNKNOWN_BOUND, unknown.get());
        }
        return consistency(new AgentContract(Map.copyOf(values)));
    }

    private static Outcome consistency(AgentContract contract) {
        final long session = contract.value(ContractLimit.MAXIMUM_EVENT_STREAM_SESSION_MILLISECONDS);
        final long resumable = contract.value(ContractLimit.HEARTBEAT_TIMEOUT_MILLISECONDS)
                * contract.value(ContractLimit.MAXIMUM_AUTOMATIC_RETRY_ATTEMPTS);
        if (session >= resumable) {
            return new Refused(Failure.INCONSISTENT_BOUND, "an event-stream session of " + session
                    + " milliseconds is not resumable within the client's own retry policy of "
                    + resumable + " milliseconds");
        }
        final Optional<Refused> share = perCallerShare(contract);
        return share.isPresent() ? share.get() : new Loaded(contract);
    }

    private static Optional<Refused> perCallerShare(AgentContract contract) {
        return List.of(ContractLimit.values()).stream()
                .filter(limit -> limit.key().startsWith(CALLER_PREFIX))
                .flatMap(limit -> totalOf(limit).stream()
                        .filter(total -> contract.value(limit) > contract.value(total))
                        .map(total -> new Refused(Failure.INCONSISTENT_BOUND,
                                limit.key() + " of " + contract.value(limit) + " is above "
                                        + total.key() + " of " + contract.value(total))))
                .findFirst();
    }

    /** How a per-caller share is spelled: the total's own name with this in front of it. */
    private static final String CALLER_PREFIX = "maximum_caller_";

    private static Optional<ContractLimit> totalOf(ContractLimit share) {
        final String total = "maximum_" + share.key().substring(CALLER_PREFIX.length());
        return List.of(ContractLimit.values()).stream()
                .filter(limit -> limit.key().equals(total))
                .findFirst();
    }

    private static String stripComment(String line) {
        final int comment = line.indexOf(COMMENT);
        return comment < 0 ? line : line.substring(0, comment);
    }

    private static Optional<Long> wholeNumber(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (final NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    private static Optional<byte[]> resource(String name) {
        try (InputStream stream = AgentContract.class.getResourceAsStream(name)) {
            return stream == null ? Optional.empty() : Optional.of(stream.readAllBytes());
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
