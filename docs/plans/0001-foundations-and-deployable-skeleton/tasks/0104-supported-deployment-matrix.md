---
id: supported-deployment-matrix
title: "Supported Deployment Matrix"
workstream: "0001"
kind: task
depends_on:
  - java-bytecode-contract
gated: false
touches:
  - support/deployments.toml
  - development/src/main/java/rs/slingshot/agent/development/DeploymentMatrix.java
  - development/src/test/java/rs/slingshot/agent/development/DeploymentMatrixTest.java
  - "development/src/test/resources/fixtures/deployment-matrix/**"
status: done
merged_as: ""
---
# Supported Deployment Matrix

A row in a table is a declaration and not evidence. Writing the rows down first is what makes the difference visible later, when one of them has a tier that ran against it and the others do not.

**Steps:**

1. Author the matrix fixtures before the file: an accepted row set, a row whose Java runtime is below the bytecode target, a row naming a tier that does not exist, and a duplicate row.
2. Write `support/deployments.toml` with one row per supported deployment, each naming the product, its Java runtime, its Sling and Oak versions, whether it is clustered, the context prefix a route is reached under, the request window its gateway allows before it ends a request whether or not the request is still moving, and which interop tier can observe it. The window is here rather than in the contract because it is a fact about somebody else's environment; what this build does with it — bounding how long a command may run, and how long a stream may stay open — is the contract's.
3. Declare the Adobe Experience Manager as a Cloud Service row as the one this product is built for, and any additional row as declared-only, with no evidence field a row can set for itself.
4. Implement the matrix reader as the only interface anything uses to ask what is supported, and refuse a second copy of a row's values elsewhere in the repository.
5. Cross-check the matrix against the bytecode contract: a release level above any row's Java runtime is a refusal that names both, so adding a row forces the decision rather than deferring it.

**Tests:**

- The accepted matrix parses into the exact declared rows, in the file's order, with every field required and none inferred.
- A row whose Java runtime is below the release level is rejected and the message names the row and both versions.
- A row naming an unknown tier, a duplicate row, and a row with a missing field are each rejected distinctly, the request window included among the required fields.
- No row can declare itself proved: a fixture carrying an evidence field is rejected as an unknown key.
- Exactly one row is marked as the built-for deployment, and a fixture with two is rejected.

- **Done when:** `./mvnw verify -pl development -Dtest=DeploymentMatrixTest` proves the exact row set parses, that a below-target Java runtime, an unknown tier, a duplicate, a missing field, a self-declared evidence field, and a second built-for row are each rejected by name.
