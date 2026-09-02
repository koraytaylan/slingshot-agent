// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.identity.CommandContractIdentity;

/**
 * Which handler runs one command, decided after the contract has been verified and not before.
 *
 * <p>The order is the point. A handler is resolved by wire name only once the five-field identity
 * has been checked against the row, so a handler is never reached by a submission whose contract
 * this build does not hold — and "the wrong version of a command ran" is not a thing that can
 * happen here rather than a thing that has not happened yet.</p>
 *
 * <p>The correspondence between rows and handlers is checked in both directions at registration.
 * A row nothing runs is a command a client can submit and nothing answers; a handler no row
 * declares is code nobody can reach; and two handlers for one name is a coin toss written down.
 * </p>
 */
public final class CommandDispatch {

    private final CommandRegistry registry;
    private final SequencedMap<String, CommandHandler> handlers;

    private CommandDispatch(CommandRegistry registry,
                            SequencedMap<String, CommandHandler> handlers) {
        this.registry = registry;
        this.handlers = handlers;
    }

    /** The result of registering handlers against a registry. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A dispatch every row and every handler of which corresponds.
     *
     * @param dispatch the dispatch
     */
    public record Held(CommandDispatch dispatch) implements Outcome {
    }

    /**
     * No dispatch, and exactly why.
     *
     * @param refusal why not
     * @param detail what was refused, named so somebody can fix it
     */
    public record Refused(DispatchRefusal refusal, String detail) implements Outcome {
    }

    /**
     * What one dispatch asked for produced.
     */
    public sealed interface Resolution permits Resolved, NotResolved {
    }

    /**
     * The handler that runs this command.
     *
     * @param handler the handler
     * @param row the row it was resolved against
     */
    public record Resolved(CommandHandler handler, RegistryRow row) implements Resolution {
    }

    /**
     * No handler, and exactly why.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record NotResolved(DispatchRefusal refusal, String detail) implements Resolution {
    }

    /**
     * Registers every handler against every row, in both directions.
     *
     * @param registry every command this build declares
     * @param handlers what runs each of them, by wire name
     * @return the dispatch, or the one reason there is none
     */
    public static Outcome of(CommandRegistry registry,
                             SequencedMap<String, CommandHandler> handlers) {
        final Optional<String> unrun = registry.wireNames().stream()
                .filter(name -> !handlers.containsKey(name))
                .findFirst();
        if (unrun.isPresent()) {
            return new Refused(DispatchRefusal.ROW_WITH_NO_HANDLER, unrun.get()
                    + " is declared and nothing here runs it, so a caller could submit it and"
                    + " wait for an answer nothing produces");
        }
        final Optional<String> undeclared = handlers.keySet().stream()
                .filter(name -> registry.row(name).isEmpty())
                .findFirst();
        if (undeclared.isPresent()) {
            return new Refused(DispatchRefusal.HANDLER_WITH_NO_ROW, undeclared.get()
                    + " is run here and no row declares it, so nothing about it is bounded");
        }
        return corresponding(registry, handlers);
    }

    /**
     * Registers handlers one at a time, refusing a second one for a name rather than picking.
     *
     * @param registry every command this build declares
     * @param registrations what runs each of them, in registration order
     * @return the dispatch, or the one reason there is none
     */
    public static Outcome from(CommandRegistry registry, List<Registration> registrations) {
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        for (final Registration registration : registrations) {
            if (handlers.put(registration.wireName(), registration.handler()) != null) {
                return new Refused(DispatchRefusal.TWO_HANDLERS_FOR_ONE_NAME,
                        registration.wireName() + " is claimed by more than one handler, and"
                                + " picking one would be picking whichever was registered last");
            }
        }
        return of(registry, handlers);
    }

    /**
     * One handler offered for one wire name.
     *
     * @param wireName the command it claims to run
     * @param handler what runs it
     */
    public record Registration(String wireName, CommandHandler handler) {
    }

    private static Outcome corresponding(CommandRegistry registry,
                                         SequencedMap<String, CommandHandler> handlers) {
        for (final RegistryRow row : registry.rows()) {
            final List<String> produced = handlers.get(row.wireName()).categories();
            final Optional<String> unnamed = produced.stream()
                    .filter(category -> !row.failureCategories().contains(category))
                    .findFirst();
            if (unnamed.isPresent()) {
                return new Refused(DispatchRefusal.CATEGORY_NO_ROW_DECLARES, row.wireName()
                        + " can fail with " + unnamed.get() + " and its row does not declare it,"
                        + " so a client could receive a failure it cannot name");
            }
            final Optional<String> unproduced = row.failureCategories().stream()
                    .filter(category -> !produced.contains(category))
                    .findFirst();
            if (unproduced.isPresent()) {
                return new Refused(DispatchRefusal.CATEGORY_NO_HANDLER_PRODUCES, row.wireName()
                        + " declares " + unproduced.get() + " and nothing can produce it, so the"
                        + " row describes a command that does not exist");
            }
        }
        return new Held(new CommandDispatch(registry, handlers));
    }

    /**
     * Which handler runs one submission, once its contract has been verified.
     *
     * <p>Verification first, always. The identity is compared with the row's own before a handler
     * is looked up, so a submission naming a contract this build does not hold reaches nothing.
     * </p>
     *
     * @param identity the five fields the submission named
     * @param bounds the two length bounds an identity is read under
     * @return the handler and its row, or the one reason there is none
     */
    public Resolution resolve(CommandContractIdentity identity,
                              CommandContractIdentity.Bounds bounds) {
        final Optional<RegistryRow> row = registry.row(identity.wireName());
        if (row.isEmpty()) {
            return new NotResolved(DispatchRefusal.HANDLER_WITH_NO_ROW,
                    "no row declares " + identity.wireName());
        }
        final CommandContractIdentity.Outcome held = row.get().identity(bounds);
        if (!(held instanceof final CommandContractIdentity.Held declared)
                || !declared.identity().equals(identity)) {
            return new NotResolved(DispatchRefusal.IDENTITY_NOT_VERIFIED, identity.wireName()
                    + " was submitted under a contract this build does not hold");
        }
        return new Resolved(handlers.get(identity.wireName()), row.get());
    }

    /**
     * Every command this dispatch can run, in wire order.
     *
     * @return the names
     */
    public List<String> wireNames() {
        return registry.wireNames();
    }

    /**
     * The one reason there is no dispatch, where there is none.
     *
     * @param outcome what registering produced
     * @return the refusal, or nothing where there is a dispatch
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
