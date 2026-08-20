---
name: bankto-payment-change
description: Standardized BankTO workflow for safely planning, implementing, testing, reviewing, and documenting payment and ledger changes across modern services and legacy systems.
disable-model-invocation: true
---

Follow this approved BankTO engineering workflow when invoked via `/bankto-payment-change`. The goal is to make advanced agentic engineering repeatable across BankTO without requiring every developer to construct a sophisticated prompt from scratch.

# BankTO Payment Change Workflow

## 1. Understand the business requirement

- Begin from the business requirement or engineering ticket.
- Summarize the intended business outcome in plain language.
- Do not begin by assuming which files need to change.

## 2. Resolve material ambiguity

Ask the engineer for clarification before implementation when ambiguity could materially affect:

- financial behavior
- API compatibility
- authentication or authorization
- persistence semantics
- customer-visible behavior
- regulatory or risk controls

Do not stop for trivial implementation details the Agent can safely determine from the repository.

## 3. Investigate before modifying

Trace the relevant architecture before changing code.

Identify:

- affected modern services
- affected legacy or monolithic paths
- authoritative server-side enforcement boundaries
- persistence boundaries
- monetary representation
- authentication and authorization boundaries
- relevant existing automated tests
- known testing gaps

## 4. Plan complex or high-risk changes

For complex or financially sensitive changes:

- produce a reviewable implementation plan before modifying business logic
- identify assumptions
- identify affected components
- identify API or backwards-compatibility implications
- identify risks
- identify required verification
- wait for human approval before implementation

## 5. Identify safe concurrency

Determine whether the work contains independent workstreams that can safely execute concurrently.

For each independent workstream identify:

- responsibility
- affected component
- dependencies
- verification required

Do not parallelize work that has genuine dependencies merely for speed.

## 6. Implement safely

After approval:

- follow all applicable BankTO repository Rules
- implement the minimum safe change
- avoid unrelated refactoring
- preserve existing behavior outside the ticket scope
- preserve backwards compatibility unless explicitly authorized

## 7. Verify the outcome

- Run the narrowest relevant automated tests first.
- Run broader affected-module tests when practical.
- Add regression coverage for changed payment behavior.
- Never weaken, remove, skip, or alter tests simply to obtain a successful result.
- Treat compilation alone as insufficient evidence for behavioral payment changes.

## 8. Prepare for human review

At completion, report:

### BUSINESS REQUIREMENT
What business outcome was requested.

### ARCHITECTURE AFFECTED
Modern services, legacy paths, APIs, and persistence boundaries affected.

### IMPLEMENTATION
What changed and why.

### TESTS EXECUTED
Exact commands and results.

### VERIFICATION EVIDENCE
Evidence that the requested behavior works and existing behavior remains intact.

### BACKWARDS COMPATIBILITY
Any compatibility impact.

### RESIDUAL RISKS
Anything not completely proven or remaining for human consideration.

### ROLLOUT
Recommended rollout considerations.

### ROLLBACK
Recommended rollback approach.

## 9. Escalate responsibly

Stop and request human guidance when a decision cannot safely be inferred, particularly when it could materially affect:

- financial correctness
- security
- customer funds
- regulatory behavior
- irreversible persistence
- backwards compatibility
