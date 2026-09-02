---
id: request-body-bounds
title: "Request Body Bounds"
workstream: "0013"
kind: task
depends_on:
  - cross-site-request-forgery
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/BoundedRequestBody.java
  - core/src/main/java/rs/slingshot/agent/http/FramingPolicy.java
  - core/src/test/java/rs/slingshot/agent/http/BoundedRequestBodyTest.java
  - "core/src/test/resources/fixtures/request-body/**"
status: done
merged_as: ""
---
# Request Body Bounds

A limit checked after a body is collected is a limit on nothing, and a bound on a length nobody knows is not a bound. Both of those are the client's own rules about response heads, applied here to request bodies for the same reasons.

**Steps:**

1. Author fixtures for a body at and one past the submission bound, a body whose declared length differs from its actual length, ambiguous framing, an unrequested content coding, and a body on a route that takes none.
2. Implement `BoundedRequestBody` reading incrementally and refusing the moment the next byte would cross the contract's submission bound, without buffering the whole body first.
3. Implement `FramingPolicy` to refuse a length and a chunked encoding together rather than resolving them, because resolving is choosing which of two senders to believe.
4. Refuse a content coding this side did not ask for, rather than decoding it, since a decoded length nobody knows cannot be bounded.
5. Refuse a body on a route that takes none, rather than ignoring it, because an ignored body is a caller believing something was read.

**Tests:**

- A body at exactly the bound is accepted and one byte past is refused, with the refusal proved to happen before the whole body is read.
- A declared length that differs from the actual length is refused, in both directions.
- Ambiguous framing and an unrequested content coding are two distinct refusals, neither of which decodes anything.
- A body on a bodiless route is refused naming the route.
- The bound is proved read from the contract, with none declared in this module.

- **Done when:** `./mvnw verify -pl core -Dtest=BoundedRequestBodyTest` proves both sides of the submission bound with refusal before full consumption, length disagreement refused in both directions, distinct framing and coding refusals with no decoding, a refused body on a bodiless route, and no bound declared outside the contract accessor.
