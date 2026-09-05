# Review evidence and reproduction

## Baseline and proof levels

Reviewed source: **11d5fc9fd04614b63c959ac0369748b32e34f126**, clean before this documentation
change, observed on **2026-09-05**. No product source or existing tests were modified.

| Evidence | What ran | What it proves and does not prove |
|---|---|---|
| Installed assembly | Pinned public Apache Sling image; installed core bundle | Current OSGi activation/discovery/refusal behavior. Does not prove AEM adapters or client interoperability. |
| HTTP probes | Current servlet code, repository test helpers, embedded JCR_OAK | Real durable state transitions and response decisions; HTTP transport itself is a double. Ownership probe uses an actual non-admin Oak login and explicit read ACL. |
| Store probes | Current code, two sessions on embedded JCR_OAK, intercepted real save boundaries | Concrete contention/partial-commit counterexamples. Needs shared DocumentNodeStore validation for the eventual multi-node repair. |
| Command probes | Freshly compiled current core sources, Sling resource resolver doubles | Actual handlers' decisions, mutations, paging and iterator consumption. Does not prove native AEM content semantics. |
| Static call-path review | Product sources, bundle descriptors, schemas, configs and tests | Directly missing consumers/registrations and ordering of publication/deadline checks. No crash/socket run claimed for the intake-digest or blocked-I/O findings. |

The core jar used in the live probe has SHA-256
82a607b9c3900b396a1d983d1c5aa76c4358a97124bc49eedc5c8dbc42648df6.
All **1,249 class entries** matched the current test-compiled core output byte for byte.
Command probes were additionally rerun after compiling every current core source with javac;
store probes compiled current store/execution/continuation sources.

## Gate result

Commands actually run:

~~~sh
scripts/quality
./mvnw --offline --batch-mode --no-transfer-progress -Dmaven.repo.local=.dependency-cache -pl core,aem -am test-compile
scripts/quality
~~~

The first quality invocation ran inside the filesystem sandbox. Dependency cache verification
passed, but Podman could not access its runtime configuration; the image checker suppressed the
engine error and reported images not present. A subsequent read-only Podman inspection outside
that restriction found the exact pinned public image. No image download was needed.

The second quality invocation had local container-engine access. Input verification, formatting,
compilation, static analysis and the early source-policy stages passed. It stopped at
tests-and-coverage-floor with:

~~~text
Tests run: 857, Failures: 2, Errors: 0, Skipped: 0
HighWaterServletTest.alivesubscriptionAnswersItsOwnCursor:
  expected: <200> but was: <410>
HighWaterServletTest.noanswerNamesAnotherSubscription:
  expected: <3> but was: <0>
~~~

This is a failed full gate, not a claimed pass. It did not reach public-interop-tier or subsequent
stages. The standalone live probe below is separate evidence. The expiry source/test mismatch is
recorded in R16; no tests were disabled or changed to obtain a cleaner baseline.

## Observed counterexamples

### Installed assembly — R01

~~~text
Local submission provenance=Held; operation identity=Held
capabilities status=200
command_contracts=[]
continuation_authority_ready=false
agent_event_store_generation=1
valid query_paths submission status=400 bodyLength=0
core bundle=Optional[Active]; components status=200
owned container stopped
~~~

Eight active DS components belonged to the bundle, all HTTP servlets/alias. The jar held zero
command-registry TOML rows. PublicSlingTier creates the administrators group and adds its admin
caller before reporting Running. Authorization refusal is 403, so the observed 400 is not that
refusal. The exact rejection branch was not instrumented; the no-op constructor and provider are
independently visible in source.

### Request lifecycle, ownership and stream progress — R03/R04/R06

~~~text
CAPACITY first=503 retry=202 runs=0 state=ACCEPTED
EVENT_QUOTA=Admitted[quantity=EVENT_ROWS, amount=1048561]
TERMINAL status=202 state=RUNNING resultPresent=false
STREAM status=200 emittedSucceeded=true highWater=NOTHING_SHOWN_YET
OWNERSHIP reader=review-reader adminGroupMember=false status=200
~~~

The ownership response contained the other operation's identifier and succeeded snapshot.
The probe grants this reader jcr:read on /var/slingshot-agent and no administrator membership.
That condition is part of the reproduction.

A numeric generation of 9223372036854775808 also escaped OperationLookupServlet as an uncaught
NumberFormatException. This lower-impact malformed-input case is preserved in the probe but was
not promoted into a separate hardening workstream.

### Store invariants — R02/R08/R09/R10/R14

~~~text
Nested identical CAS=WRITTEN
Outer identical CAS=WRITTEN
Nested submission admission=Accepted
Outer submission admission=Accepted
Nested operation start=Held
Outer operation start=Held
After two racing releases total=0 share=1
Actual lease=Taken[holder=real-holder, heldUntilUnixMilliseconds=31000]
Unowned lease compareAndSet=Written
Fence after restart has owner=false
Take after expiry=Lost
Interrupted sweep artifact exists=true rows=0 bytes=0
Repeated sweep artifact exists=false rows=-1 bytes=-4
Sweep bound=1 actual examined=2
Two subscription end calls rows=-1
Interrupted rotation serving=2 served=[1]
access to previous=Retired[named=1, serving=2]
~~~

The store probe pauses one real operation at a chosen session method, completes the other session's
operation, and resumes the first. It does not rely on probabilistic thread scheduling. Save-failure
cases discard transient state before inspecting what another operation can read. Repeated cleanup
is then exercised against that surviving durable state.

### Command decisions — R11/R12/R13/R14

~~~text
references at budget 1=0; budget 100=1
delete with refuse_when_referenced=Produced; target exists=false
retained link=/content/target
list initial offset 1 limit 1=Produced: only /content/pages/p1, no next token
list invalid continuation=Produced: /content/pages/p0, p1, p2
query initial offset 1 limit 1=Produced: /content/pages, p0, p1, p2
under bound 1 found=2; child iterator consumed=10000
negative window=Held[window=Initial[offset=-1, limit=-1]]
delete_component ordinary folder with discovery=1=Produced
removed_node_count=6; folder exists=false
~~~

The small injected discovery budget makes the missed-reference case deterministic. The corresponding
normal default is 1,024 nodes. The pagination observations invoke handler run paths, rather than
calling the standalone paging helper that those paths currently bypass.

## Re-run the preserved probes

Sources are embedded in these documents so evidence survives deletion of temporary files:

- [HTTP, terminal and ownership probes](evidence/http-probes.md)
- [Store probe](evidence/store-probe.md)
- [Command probe](evidence/command-probe.md)
- [Installed assembly probe](evidence/live-probe.md)

Run from the repository root against the reviewed source. First use the normal prepared offline
cache and run the full gate. The current known expiry failure still produces the core Surefire
reports from which the test classpath below is read. A report supplies only dependency paths;
fresh compilation and the explicit product-classpath prefix determine the code under test.
The following focused reproduction is not a replacement for scripts/quality:

~~~sh
./mvnw --offline --batch-mode --no-transfer-progress -Dmaven.repo.local=.dependency-cache -pl core,aem -am test-compile
python3 - <<'PY'
from pathlib import Path
import re
import tempfile
import xml.etree.ElementTree as E
root = Path.cwd()
out = Path(tempfile.mkdtemp(prefix="slingshot-hardening-"))
reports = list((root / "core/target/surefire-reports").glob("TEST-*.xml"))
assert reports, "Run scripts/quality to produce current core test reports first."
props = E.parse(reports[0]).findall("./properties/property")
classpath = next(p.attrib["value"] for p in props if p.attrib["name"] == "java.class.path")
(out / "classpath").write_text(classpath)
(out / "product-sources").write_text("\n".join(str(p.resolve()) for p in
    (root / "core/src/main/java").rglob("*.java")))
bundle = root / "docs/plans/0010-runtime-hardening-and-stabilisation/evidence"
for doc in bundle.glob("*.md"):
    for filename, code in re.findall(r"## ([A-Za-z]+\.java)\n\n~~~java\n(.*?)\n~~~", doc.read_text(), re.S):
        (out / filename).write_text(code)
(root / "target").mkdir(exist_ok=True)
(root / "target/hardening-probe-directory").write_text(str(out))
print(out)
PY
review_dir=$(cat target/hardening-probe-directory)
review_cp=$(cat "$review_dir/classpath")
mkdir -p "$review_dir/product" "$review_dir/probes"
javac -cp "$review_cp" -d "$review_dir/product" @"$review_dir/product-sources"
javac -cp "$review_dir/product:$review_cp" -d "$review_dir/probes" \
    "$review_dir/HttpRuntimeProbe.java" "$review_dir/OwnershipProbe.java" \
    "$review_dir/TerminalProbe.java" "$review_dir/StoreReviewProbe.java" \
    "$review_dir/CommandReviewProbe.java"
java -cp "$review_dir/probes:$review_dir/product:$review_cp" rs.slingshot.agent.http.HttpRuntimeProbe
java -cp "$review_dir/probes:$review_dir/product:$review_cp" rs.slingshot.agent.http.OwnershipProbe
java -cp "$review_dir/probes:$review_dir/product:$review_cp" rs.slingshot.agent.http.TerminalProbe
java -cp "$review_dir/probes:$review_dir/product:$review_cp" StoreReviewProbe
java -cp "$review_dir/probes:$review_dir/product:$review_cp" CommandReviewProbe
~~~

The live probe additionally requires compiled interop classes and an existing core jar whose class
entries match the reviewed build, plus the already-prepared pinned image. It uses the repository's
PublicSlingTier harness and stops only its own container in finally. With the focused harness above
prepared, compile interop and run:

~~~sh
./mvnw --offline --batch-mode --no-transfer-progress -Dmaven.repo.local=.dependency-cache -pl interop -am test-compile
javac -cp "interop/target/classes:$review_dir/product:$review_cp" -d "$review_dir/probes" "$review_dir/LiveAssemblyProbe.java"
java -cp "interop/target/classes:$review_dir/probes:$review_dir/product:$review_cp" LiveAssemblyProbe "$PWD" "$review_dir"
~~~

AEM quickstart, sibling client, full populated author console, shared DocumentNodeStore contention,
blocked socket deadlines, and power-loss intake validation were **not** executed as part of this
review. Tasks explicitly require those additional proofs where needed.

## Coverage and filtering

The review traced all registered HTTP route families, their store/session consumers, request and
stream lifecycles, the shared execution/storage primitives, command families and shared mutation/
paging traversal utilities, AEM adapter registration, package/configuration assembly, console data
sources, and the interop/gate paths. Representative handler probes focus on shared failure
mechanisms; this is not a claim that each of sixty-four commands has passed a native AEM scenario.

Minor input/parser edge cases, stale narrative wording and speculative deployment exploits were
not promoted into findings. Each P1/P2 finding has a concrete observed counterexample or a direct
source-level missing transition/consumer, with its evidence level stated.

## Deliverable validation

The reproduction instructions above were extracted from this document and executed successfully
against freshly compiled core sources. They reproduced the reported HTTP, ownership, store and
command counterexamples. The extracted live probe also compiled with the documented classpath;
the separately recorded installed-container run supplies its runtime evidence.

Local structural checks passed for all 27 task frontmatters, filenames, workstreams, ordered steps,
single completion criteria, existing relative mutation footprints and acyclic dependencies. All
17 findings reference authored tasks, the status count agrees, and all 36 Markdown documents have
valid local links and no trailing whitespace. Only this new plan directory differs from the
reviewed worktree. Makina registration and its coordinator validation were not invoked; these
checks establish the documented local structure, not a registered execution or integrated repair.
