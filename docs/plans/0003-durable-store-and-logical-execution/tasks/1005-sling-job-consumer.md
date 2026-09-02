---
id: sling-job-consumer
title: "Sling Job Consumer"
workstream: "0010"
kind: task
depends_on:
  - worker-execution-fence
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/execution/CommandJobConsumer.java
  - core/src/main/java/rs/slingshot/agent/execution/CommandJobTopic.java
  - core/src/main/java/rs/slingshot/agent/execution/JobEnqueue.java
  - "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/**"
  - core/src/test/java/rs/slingshot/agent/execution/CommandJobConsumerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/JobRedeliveryScenario.java
  - interop/scenarios/job-redelivery.toml
status: done
merged_as: ""
---
# Sling Job Consumer

The job system's role is smaller than it looks, and smaller again than it was. A command that finishes inside its own request does not need one at all, and every command this product ships is one of those — so this path exists for the deferred class, carries no shipped command, and is proved by the interop tier's own fake command rather than by a real one.

Building it anyway is a decision worth stating. The alternative was to leave it out and add it when the first long-running command arrives, which would mean designing the durable half of this product at the moment somebody is in a hurry. What it carries is a physical attempt to a node that can execute it, and that is all: it is not the record of the work, it is not the idempotency mechanism, and its retry policy is not the operation's.

**Steps:**

1. Author fixtures for a job carrying a known operation, one carrying an unknown operation, one carrying a foreign generation, one redelivered after completion, and one whose properties are malformed.
2. Implement `CommandJobTopic` as one declared topic and configure its queue for the deployment rows the matrix declares, with the concurrency, retry, and retry-delay values read from the contract.
3. Implement `JobEnqueue` to enqueue a deferred row and nothing else — an immediate row is executed by the route that admitted it and never reaches a queue — with every durable fact written and committed before a job is enqueued, and prove the ordering rather than asserting it: a store that has the record and no job recovers; a job with no record does not. An operation whose artifact manifest declares intake slots is not enqueued until every declared slot is complete, so a command never starts against a payload that has not finished arriving.
4. Implement `CommandJobConsumer` to load the operation by path, record the physical attempt, take the fence, and hand off to execution, returning the job outcome that matches what actually happened rather than always succeeding.
5. Make a job for an unknown operation, a foreign generation, or malformed properties a job that is dropped rather than retried forever, each recorded distinctly, since none of them will become valid by being tried again.
6. Refuse a job for a row whose execution class is not `Deferred`, as its own recorded reason, so an immediate command can never reach this path by any route — including a job crafted by hand.

**Tests:**

- The topic and its queue configuration are asserted equal to the declared values, and every value is proved read from the contract rather than written here.
- Enqueue is proved to follow the durable write, by a fault injected between them that leaves a recoverable record and no job.
- An immediate row is proved never enqueued and a job naming one is refused with its own reason; a deferred row is enqueued and consumed, proved with the interop tier's fake command since no shipped command is deferred.
- An operation with an incomplete intake manifest is proved not enqueued, and completing its last declared slot is proved to be what enqueues it.
- A job carrying an unknown operation, a foreign generation, or malformed properties is dropped rather than retried, each with its own recorded reason.
- A job redelivered after completion is recorded as a duplicate attempt and does not re-execute, proved on a running instance.
- A job whose fence is already held returns the outcome that lets the job system retry later rather than one that discards it.
- The queue is proved empty across a workload of every shipped command, since none of them is deferred.

- **Done when:** `./mvnw verify -pl core -Dtest=CommandJobConsumerTest && ./mvnw verify -pl interop -Dtest=JobRedeliveryScenario` proves contract-read queue configuration, a durable write ordered before enqueue under an injected fault, an incomplete intake manifest holding an operation out of the queue until its last slot completes, three distinct drop reasons with no retry, an immediate row never enqueued and a hand-crafted job naming one refused, an empty queue across a workload of every shipped command, no re-execution on post-completion redelivery on a running instance, and a retryable outcome when the fence is held.
