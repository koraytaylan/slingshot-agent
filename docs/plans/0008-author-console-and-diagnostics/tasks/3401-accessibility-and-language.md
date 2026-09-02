---
id: accessibility-and-language
title: "Accessibility and Language"
workstream: "0034"
kind: task
depends_on:
  - capacity-and-retention-view
gated: false
touches:
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/i18n/**"
  - development/src/main/java/rs/slingshot/agent/development/ConsoleAccessibility.java
  - development/src/test/java/rs/slingshot/agent/development/ConsoleAccessibilityTest.java
  - "development/src/test/resources/fixtures/console-accessibility/**"
status: done
merged_as: ""
---
# Accessibility and Language

Retrofitting either of these is far more expensive than doing them once, and a console with five pages is exactly the size where it is still cheap. Granite renders server-side, so both are checkable from the markup rather than from a browser.

**Steps:**

1. Author fixtures for a page with an unlabelled control, one with a literal string, one with a table lacking header associations, and one with an image lacking a text alternative.
2. Externalise every console string into the translation dictionary, including titles, column headings, empty-state messages, and every failure message the console renders.
3. Implement `ConsoleAccessibility` over the server-rendered markup of every console page: every interactive control has an accessible name, every table associates its headers with its cells, every image has a text alternative, and the reading order matches the visual order.
4. Assert every page is operable without a pointing device, by checking that every interactive control is focusable and no control is reachable only through a hover-revealed container.
5. Refuse a literal string in any console resource or data source, so the dictionary cannot be bypassed later.

**Tests:**

- Every console string resolves from the dictionary, and a literal in any console resource or data source is rejected naming it.
- Every interactive control has an accessible name; a fixture page with an unlabelled control fails naming it.
- Every table associates headers with cells and every image has a text alternative, each proved against a fixture lacking it.
- No control is reachable only through a hover-revealed container, proved over the rendered markup of every page.
- The dictionary is complete for the base language, and a key used and not declared, or declared and not used, are two distinct findings.

- **Done when:** `./mvnw verify -pl development -Dtest=ConsoleAccessibilityTest` proves every console string dictionary-sourced with literals refused, an accessible name on every control, header associations and text alternatives on every table and image, no hover-only reachable control, and two-way dictionary key correspondence.
