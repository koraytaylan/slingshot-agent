// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.proof;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.Privilege;
import javax.servlet.Servlet;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.TerminalCommit;
import rs.slingshot.agent.http.StateAuthority;
import rs.slingshot.agent.http.SubmitServlet;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.repository.AgentSession;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.StatePath;

/** Test fixtures linked to the installed product's class loader, without copying product classes. */
public final class StateAccessFixtures {

    private static final AgentContract CONTRACT = ((AgentContract.Loaded) AgentContract.load()).contract();
    private static final String CONTENT = "/content/state-access-proof";
    private static final List<String> USERS = List.of("proof-owner", "proof-operator", "proof-reader",
            "proof-removed");
    private static final AtomicReference<String> EFFECT = new AtomicReference<>("not-run");

    private StateAccessFixtures() {
    }

    /** Supplies the installed submission implementation with a test command using its received session. */
    public static Servlet submission() {
        return new SubmitServlet(new ContentAttempt());
    }

    /** Reports the identity and the actual attempted content write observed by the test command. */
    public static String effect() {
        return EFFECT.get();
    }

    /** Creates real callers with state read access and explicit denial of state and content writes. */
    public static String prepare(Session session) throws RepositoryException, IOException {
        final var users = ((JackrabbitSession) session).getUserManager();
        final var group = users.createGroup("proof-state-operators");
        final Node content = session.getNode("/content").addNode("state-access-proof", "nt:unstructured");
        content.setProperty("value", "original content bytes");
        for (final String name : USERS) {
            final var user = users.createUser(name, "proof-password");
            if (!"proof-reader".equals(name)) {
                group.addMember(user);
            }
            grant(session, StatePath.ROOT, user.getPrincipal());
            grant(session, CONTENT, user.getPrincipal());
        }
        session.save();
        GenerationStore.establish(session);
        for (final String name : USERS) {
            final StatePath.Caller caller = ((StatePath.Held) StatePath.caller(name)).caller();
            for (final AccountedQuantity quantity : AccountedQuantity.values()) {
                CapacityLedger.prepare(session, quantity, caller);
            }
        }
        final AgentSession.Completion scoped = AgentSession.current().withState(state -> {
            require(state.hasPermission(StatePath.ROOT, "set_property"), "state service cannot write state");
            require(!state.hasPermission(CONTENT, "set_property"), "state service can modify caller content");
        });
        require(scoped == AgentSession.Completion.COMPLETED, "the installed state service is unavailable");
        return "prepared";
    }

    private static void grant(Session session, String path, Principal principal) throws RepositoryException {
        final var access = session.getAccessControlManager();
        final var policies = access.getPolicies(path);
        final var policy = (JackrabbitAccessControlList) (policies.length == 0
                ? access.getApplicablePolicies(path).nextAccessControlPolicy() : policies[0]);
        policy.addEntry(principal, new Privilege[] { access.privilegeFromName(Privilege.JCR_READ) }, true);
        policy.addEntry(principal, new Privilege[] { access.privilegeFromName(Privilege.JCR_WRITE),
                access.privilegeFromName(Privilege.JCR_NODE_TYPE_MANAGEMENT) }, false);
        access.setPolicy(path, policy);
    }

    /** Revokes a real group membership while retaining the caller's state-tree read grant. */
    public static String remove(Session session) throws RepositoryException {
        final var users = ((JackrabbitSession) session).getUserManager();
        final var group = (org.apache.jackrabbit.api.security.user.Group)
                Objects.requireNonNull(users.getAuthorizable("proof-state-operators"));
        require(group.removeMember(Objects.requireNonNull(users.getAuthorizable("proof-removed"))),
                "membership was not removed");
        session.save();
        return "removed";
    }

    /** Seeds a referenced artifact through the installed artifact store for the transfer route matrix. */
    public static String artifact(Session session, String identifier) throws RepositoryException {
        final var generation = ((EventStoreGeneration.Held) EventStoreGeneration.of(1)).generation();
        final var operation = ((AgentOperationIdentifier.Held)
                AgentOperationIdentifier.of(identifier, CONTRACT)).identifier();
        final StatePath path = StatePath.operation(generation, operation);
        final var caller = StateAuthority.owner(session, path).orElseThrow();
        final var slot = ((ArtifactSlot.Held) ArtifactSlot.of("proof-answer")).slot();
        final byte[] payload = "state access artifact bytes".getBytes(StandardCharsets.UTF_8);
        require(ArtifactStore.publish(session, caller, path,
                new ArtifactStore.Publication(slot, payload.length, new ByteArrayInputStream(payload)),
                System.currentTimeMillis(), CONTRACT) instanceof ArtifactStore.Published,
                "the artifact fixture was not published");
        session.getNode(path.path()).setProperty(TerminalCommit.RESULT_SLOT, slot.name());
        session.save();
        return "artifact";
    }

    /** Attempts the requested write with the exact session the installed submission servlet supplies. */
    public static final class ContentAttempt implements SubmitServlet.Commands {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean serves(String wireName) {
            return "query_paths".equals(wireName);
        }

        @Override
        public ExecutionOutcome.Completion run(LogicalOperation operation, DocumentValue.Mapping submission,
                                            Session session) {
            try {
                require(!session.hasPermission(StatePath.ROOT, "set_property"),
                        "the command received state write authority");
                try {
                    session.getNode(CONTENT).setProperty("value", "unexpected write");
                    session.save();
                    throw new IllegalStateException("the restricted command changed content");
                } catch (final AccessDeniedException denied) {
                    session.refresh(false);
                    EFFECT.set(session.getUserID() + ":denied");
                }
                return new ExecutionOutcome.Succeeded(new ExecutionOutcome.Inline("{\"effect\":\"denied\"}"));
            } catch (final RepositoryException failed) {
                throw new IllegalStateException("the content permission proof failed", failed);
            }
        }
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }
}
