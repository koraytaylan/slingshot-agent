// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * What the agent's own identity may reach, and the two ways a session may be obtained.
 *
 * <p>The correspondence that matters runs between this file and the repository initialisation
 * script: a grant declared here and not created is a permission somebody wrote down and nobody has;
 * a grant created and not declared is one nobody reviewed. Both directions are checked, because
 * either one on its own lets the list stop describing the instance.</p>
 *
 * <p>The other rule is the one the whole access model rests on. Nothing in this repository
 * impersonates. The design that would have needed it — an agent that executed work after the
 * request that submitted it — is the design this product does not have, so the refusal costs
 * nothing and removes a standing privilege over other people's identities that somebody would
 * otherwise have had to justify.</p>
 */
public final class RepositoryAccess {

    private static final String POLICY_FILE = "policy/repository-access.toml";

    private static final String SUBSERVICE_ROWS = "subservice";

    private static final String GRANT_ROWS = "grant";

    private static final String REFUSED_ROWS = "refused_path";

    /** Where the repository initialisation script is committed. */
    public static final String REPOINIT_CONFIGURATION =
            "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/"
                    + "org.apache.sling.jcr.repoinit.RepositoryInitializer~slingshot-agent.cfg.json";

    /** Where the service user mapping is committed. */
    public static final String MAPPING_CONFIGURATION =
            "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/"
                    + "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended"
                    + "~slingshot-agent.cfg.json";

    /** Every way of acting as somebody else, refused anywhere in repository-owned Java. */
    private static final List<String> IMPERSONATION_CALLS =
            List.of("impersonate", "user.impersonation", "impersonateFromService");

    private final String serviceUser;
    private final List<String> subservices;
    private final List<GrantRow> grants;
    private final List<String> refusedPaths;

    private final PermittedGroups permittedGroups;

    private RepositoryAccess(String serviceUser, List<String> subservices, List<GrantRow> grants,
                             List<String> refusedPaths, PermittedGroups permittedGroups) {
        this.serviceUser = serviceUser;
        this.subservices = subservices;
        this.grants = grants;
        this.refusedPaths = refusedPaths;
        this.permittedGroups = permittedGroups;
    }

    /**
     * One thing the agent's own identity may do, somewhere.
     *
     * @param path where the grant applies
     * @param privileges what it permits
     * @param reason why the agent needs it
     */
    public record GrantRow(String path, List<String> privileges, String reason) {

        /**
         * Holds a grant whose privileges nothing can change afterwards.
         */
        public GrantRow {
            privileges = List.copyOf(privileges);
        }

        /**
         * What this grant permits.
         *
         * @return the privileges, as a view nothing can change
         */
        @Override
        public List<String> privileges() {
            return Collections.unmodifiableList(privileges);
        }
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(RepositoryAccess policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the access policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("repository-access")
                .text("service_user.name")
                .text("service_user.home")
                .text("service_user.reason")
                .rows(SUBSERVICE_ROWS, row -> row.text("name").text("reason"))
                .rows(GRANT_ROWS, row -> row.text("path").textList("privileges").text("reason"))
                .rows(REFUSED_ROWS, row -> row.text("path").text("reason"))
                .text("permitted_groups.configuration")
                .text("permitted_groups.property")
                .textList("permitted_groups.shipped")
                .text("permitted_groups.reason")
                .build();
    }

    /**
     * Reads the policy this repository commits.
     *
     * @param root the repository root
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readPolicy(root.resolve(POLICY_FILE));
    }

    /**
     * Reads a policy document from wherever it sits.
     *
     * @param policy the policy document
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome readPolicy(Path policy) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(policy, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        final List<GrantRow> grants = document.rows(GRANT_ROWS).stream()
                .map(row -> new GrantRow(row.text("path"), row.textList("privileges"),
                        row.text("reason")))
                .toList();
        final Optional<GrantRow> unexplained = grants.stream()
                .filter(row -> row.reason().isBlank())
                .findFirst();
        if (unexplained.isPresent()) {
            return new Refused(unexplained.get().path() + " is granted and records no reason");
        }
        final Optional<GrantRow> outside = grants.stream()
                .filter(row -> !row.path().startsWith("/var/slingshot-agent"))
                .findFirst();
        if (outside.isPresent()) {
            return new Refused(outside.get().path()
                    + " is granted to the agent's own identity and is outside its own tree");
        }
        return new Loaded(new RepositoryAccess(document.text("service_user.name"),
                document.rows(SUBSERVICE_ROWS).stream().map(row -> row.text("name")).toList(),
                grants,
                document.rows(REFUSED_ROWS).stream().map(row -> row.text("path")).toList(),
                new PermittedGroups(document.text("permitted_groups.configuration"),
                        document.text("permitted_groups.property"),
                        document.textList("permitted_groups.shipped"))));
    }

    /**
     * Who may start work on a fresh install, and where a customer's copy of that decision lives.
     *
     * @param configuration the configuration an operator widens it through
     * @param property the property in that configuration
     * @param shipped the groups this product ships naming, which is one and is not everybody
     */
    public record PermittedGroups(String configuration, String property, List<String> shipped) {

        /** Holds a list nothing can change afterwards. */
        public PermittedGroups {
            shipped = List.copyOf(shipped);
        }

        /**
         * Where a customer's copy of the shipped value sits.
         *
         * @return the path, relative to the repository root
         */
        public String shippedAt() {
            return "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/"
                    + configuration + ".cfg.json";
        }
    }

    /**
     * Who may start work, and what a customer actually receives.
     *
     * @return the declaration
     */
    public PermittedGroups permittedGroups() {
        return permittedGroups;
    }

    /**
     * Whether what this product ships is what the policy says it ships.
     *
     * <p>A default of everybody is not a default at all, and a configuration nobody can find is one
     * an operator cannot widen. So the shipped value is compared with the file a customer receives
     * rather than described beside it.</p>
     *
     * @param root the repository root
     * @return one finding per group declared and not shipped, and per group shipped and not
     *     declared
     */
    public PolicyReport againstTheShippedConfiguration(Path root) {
        final String shipped = RepositoryTree.text(root.resolve(permittedGroups.shippedAt()));
        final List<PolicyFinding> findings = new ArrayList<>();
        if (!shipped.contains("\"" + permittedGroups.property() + "\"")) {
            findings.add(PolicyFinding.inFile(permittedGroups.shippedAt(), "repository-access",
                    "the shipped configuration declares no " + permittedGroups.property()));
        }
        permittedGroups.shipped().stream()
                .filter(group -> !shipped.contains("\"" + group + "\""))
                .map(group -> PolicyFinding.inFile(permittedGroups.shippedAt(),
                        "repository-access",
                        group + " is declared as shipped and the configuration does not name it"))
                .forEach(findings::add);
        quoted(shipped).stream()
                .filter(named -> !named.equals(permittedGroups.property()))
                .filter(named -> !permittedGroups.shipped().contains(named))
                .map(named -> PolicyFinding.inFile(POLICY_FILE, "repository-access",
                        named + " is shipped as a permitted group and no row declares it"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    private static List<String> quoted(String configuration) {
        final List<String> named = new ArrayList<>();
        final java.util.regex.Matcher found =
                java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(configuration);
        while (found.find()) {
            named.add(found.group(1));
        }
        return named;
    }

    /**
     * The identity the agent's own bookkeeping runs as.
     *
     * @return the service user's name
     */
    public String serviceUser() {
        return serviceUser;
    }

    /**
     * Every subservice the agent maps to that identity.
     *
     * @return the subservice names, in the policy's own order
     */
    public List<String> subservices() {
        return Collections.unmodifiableList(subservices);
    }

    /**
     * Every grant the agent's own identity holds.
     *
     * @return the grants, in the policy's own order
     */
    public List<GrantRow> grants() {
        return Collections.unmodifiableList(grants);
    }

    /**
     * The paths the agent's own identity must be refused, whatever else changes.
     *
     * @return the refused paths, in the policy's own order
     */
    public List<String> refusedPaths() {
        return Collections.unmodifiableList(refusedPaths);
    }

    /**
     * Whether the declared grants and the ones the initialisation script creates are the same set.
     *
     * @param root the repository root
     * @return one finding per declared grant the script does not create, per privilege the script
     *     grants and nobody declared, and where the script names a different service user
     */
    public PolicyReport againstTheScript(Path root) {
        final String script = RepositoryTree.text(root.resolve(REPOINIT_CONFIGURATION));
        final List<PolicyFinding> findings = new ArrayList<>();
        if (!script.contains("create service user " + serviceUser)) {
            findings.add(PolicyFinding.inFile(REPOINIT_CONFIGURATION, "repository-access",
                    "the script creates no service user named " + serviceUser));
        }
        grants.forEach(grant -> {
            if (!script.contains("on " + grant.path())) {
                findings.add(PolicyFinding.inFile(REPOINIT_CONFIGURATION, "repository-access",
                        grant.path() + " is granted and the script grants nothing there"));
            }
            grant.privileges().stream()
                    .filter(privilege -> !script.contains(privilege))
                    .map(privilege -> PolicyFinding.inFile(REPOINIT_CONFIGURATION,
                            "repository-access",
                            privilege + " is declared on " + grant.path()
                                    + " and the script does not grant it"))
                    .forEach(findings::add);
        });
        declaredPrivileges(script).stream()
                .filter(privilege -> grants.stream()
                        .noneMatch(grant -> grant.privileges().contains(privilege)))
                .map(privilege -> PolicyFinding.inFile(POLICY_FILE, "repository-access",
                        privilege + " is granted by the script and no row declares it"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * Whether the service user mapping names only subservices the policy declares.
     *
     * @param root the repository root
     * @return one finding per mapped subservice with no row, and per row nothing maps
     */
    public PolicyReport againstTheMapping(Path root) {
        final String mapping = RepositoryTree.text(root.resolve(MAPPING_CONFIGURATION));
        final List<PolicyFinding> findings = new ArrayList<>();
        subservices.stream()
                .filter(subservice -> !mapping.contains(":" + subservice + "="))
                .map(subservice -> PolicyFinding.inFile(MAPPING_CONFIGURATION, "repository-access",
                        subservice + " is declared and nothing maps it"))
                .forEach(findings::add);
        mappedSubservices(mapping).stream()
                .filter(subservice -> !subservices.contains(subservice))
                .map(subservice -> PolicyFinding.inFile(POLICY_FILE, "repository-access",
                        subservice + " is mapped and no row declares it"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * Whether anything in repository-owned Java acts as somebody else.
     *
     * @param root the repository root
     * @return one finding per call that impersonates, wherever it appears
     */
    public static PolicyReport impersonation(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root, ".java").forEach(source -> {
            final String text = RepositoryTree.text(source);
            IMPERSONATION_CALLS.stream()
                    .filter(call -> text.contains(call + "("))
                    .map(call -> PolicyFinding.inFile(root.relativize(source).toString(),
                            "impersonation", call))
                    .forEach(findings::add);
        });
        return PolicyReport.of(findings);
    }

    private static List<String> declaredPrivileges(String script) {
        return script.lines()
                .filter(line -> line.contains("allow "))
                .flatMap(line -> List.of(line.substring(line.indexOf("allow ") + "allow ".length())
                        .split(" on ")[0].split(",")).stream())
                .map(String::strip)
                .filter(privilege -> !privilege.isEmpty())
                .toList();
    }

    private static List<String> mappedSubservices(String mapping) {
        return mapping.lines()
                .filter(line -> line.contains(":") && line.contains("="))
                .map(line -> line.substring(line.indexOf(':') + 1, line.indexOf('=')))
                .map(String::strip)
                .filter(subservice -> !subservice.isEmpty() && !subservice.contains("\""))
                .toList();
    }
}
