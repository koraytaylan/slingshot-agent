# Plan 0010 implementation evidence

This records direct implementation under the user's instruction to review and commit each task.
It does not claim Makina registration, Phase R validation, or final integration. The authored task
frontmatter remains the Makina lifecycle record; the evidence below tracks the direct work.

## 4101 — deterministic subscription time

The production no-argument servlet constructor uses `Clock.systemUTC()`. A second constructor accepts
an explicit clock, and subscription expiry uses that clock. Tests create subscription timestamps
and advance time from the same controlled source. The existing retention comparison stays inclusive:
subscriptions are live one millisecond before and exactly at the retention boundary, and expired one
millisecond after it. Cases cover 2020-01-01, 2026-09-05, and 2040-12-31.

Review and correction loop:

1. Checked the expiry predicate in `SubscriptionLedger` and preserved its strict greater-than
   comparison rather than changing the production boundary to accommodate the test.
2. Compilation found that a servlet cannot hold a non-serializable `Clock` as a normal field.
   The final clock reference is transient; `readResolve` restores a production servlet.
3. Static analysis rejected a mutable restoration field. The final implementation retains no
   mutable request state. A deserialization test was removed because the repository's security
   analyzer forbids object deserialization, including in tests; no suppression was added.
4. Updated the servlet's pattern registration from `stateless-policy` to the existing `accessor`
   pattern, because the class now holds its clock. The pattern rules and checker are unchanged.
5. Javadoc generation required documentation on the serialization hook; added it.
6. Re-read the final diff against all three task steps and checked whitespace.

Validation on 2026-09-05:

- Focused `HighWaterServletTest`: 8 tests, zero failures/errors/skips.
- Full argument-free `scripts/quality`: core completed 860 tests with zero failures/errors/skips,
  and its coverage thresholds passed. Formatting, compilation, static analysis, and the repository
  policy stages preceding the reactor verification passed. `HighWaterScenario` passed all three
  running-instance cases. The interop module finished with 455 tests, zero failures, one error.
- Independent remaining gate failure: `ArtifactTransferScenario.install` failed during public-tier
  setup because `/system/userManager/group/administrators.update.html` returned HTTP 500 while
  adding the test caller to the administrators group. No artifact request was executed by that
  scenario. This does not establish that the full gate passes; later stages were not reached.
- Task 4101 acceptance is satisfied: controlled-date expiry regressions pass, production time is
  retained, and the full gate's remaining failure is recorded. The full-plan gate remains open.
- Full gate output for this work session: `/tmp/plan10-4101-quality.log`.

No other task is claimed complete by this record.
