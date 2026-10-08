// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

/** Shared repository preparation for the submission and request-timing suites. */
final class SubmissionTestFixture {

    private static final String PERMITTED_GROUP = "administrators";

    private SubmissionTestFixture() {
    }

    /**
     * Permits the repository's own caller through its real user manager.
     * @param session the session whose user is asking
     * @throws RepositoryException if the repository fails
     */
    static void permitted(Session session) throws RepositoryException {
        final org.apache.jackrabbit.api.security.user.UserManager users =
                ((org.apache.jackrabbit.api.JackrabbitSession) session).getUserManager();
        final org.apache.jackrabbit.api.security.user.Authorizable existing =
                users.getAuthorizable(PERMITTED_GROUP);
        final org.apache.jackrabbit.api.security.user.Group group = existing == null
                ? users.createGroup(PERMITTED_GROUP)
                : (org.apache.jackrabbit.api.security.user.Group) existing;
        group.addMember(java.util.Objects.requireNonNull(
                users.getAuthorizable(session.getUserID()),
                "this repository has no authorizable for the user its own session is"));
        session.save();
    }

    /**
     * Creates the synthetic repository fixture's path with ordinary caller permissions.
     * @param session the caller's repository session
     * @param path the fixture path
     * @throws RepositoryException if the repository fails
     */
    static void walked(Session session, String path) throws RepositoryException {
        Node node = session.getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session.save();
    }
}
