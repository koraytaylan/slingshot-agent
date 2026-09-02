---
id: console-authorization
title: "Console Authorization"
workstream: "0031"
kind: task
depends_on:
  - console-shell
gated: false
touches:
  - aem/src/main/java/rs/slingshot/agent/aem/console/ConsoleAuthority.java
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/nav/.content.xml"
  - aem/src/test/java/rs/slingshot/agent/aem/console/ConsoleAuthorityTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/ConsoleAuthorizationScenario.java
  - interop/scenarios/console-authorization.toml
status: done
merged_as: ""
---
# Console Authorization

A console is a servlet, an authenticated user, and a permitted group — exactly like a route. The only thing that differs is that a tool nobody may use is better not advertised, so the entry is absent rather than present and refusing.

**Steps:**

1. Author fixtures for a permitted-group viewer, an authenticated viewer outside it, an anonymous viewer, and a direct request to a data source bypassing the page.
2. Implement `ConsoleAuthority` as the single check every console resource and every data source passes, reusing Plan 0004's authorization gate rather than a second copy of it.
3. Render the navigation entry conditionally on that check, so a viewer outside the group does not see a tool they cannot use.
4. Refuse a direct data-source request from an unauthorized viewer with the same answer a route gives, so bypassing the page buys nothing.
5. Make the check happen before any store is read, so an unauthorized viewer's request never reaches the service user's session.

**Tests:**

- The authorization gate is asserted to be the very type Plan 0004 uses, not a copy.
- An permitted-group viewer sees the entry and the pages; an authenticated viewer outside the group sees neither the entry nor a refusal page.
- A direct data-source request from an unauthorized viewer is refused identically to a route, byte for byte.
- The check is proved to precede any store read, by a store that would record any access.
- On a running instance, an anonymous request to every console resource and data source is refused.

- **Done when:** `./mvnw verify -pl aem -Dtest=ConsoleAuthorityTest && ./mvnw verify -pl interop -Dtest=ConsoleAuthorizationScenario` proves a shared rather than duplicated authorization gate, an absent entry rather than a refusal for a non-member, byte-identical refusals for direct data-source access, no store read before the check, and every console resource refusing anonymous on a running instance.
