# BankTO Agent Development Guide

## Repository context

- This repository represents BankTO's fictitious banking and payments platform and is based on Bank of Anthos.
- The modern payment architecture relevant to BankTO engineering work includes:
  - frontend
  - ledgerwriter
  - balancereader
  - transactionhistory
  - ledger-db
- The legacy implementation relevant to payment changes is:
  - ledgermonolith
- ledgerwriter is the authoritative modern transaction-write service.
- ledger-db is the ledger system of record.
- ledgermonolith combines ledger write, balance, and transaction-history responsibilities in one Java process.

## Java environment

- BankTO's Java ledger modules target JDK 17.
- Use the repository Maven Wrapper:
  `./mvnw`
- Do not depend on a globally installed `mvn`.

### macOS local Agents

Cursor Agent shells on the verified BankTO Mac environment do not automatically load `~/.zshrc`.

Before running Java/Maven commands on macOS, use:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
```

Then verify when necessary with:

```bash
"$JAVA_HOME/bin/java" -version
```

### Non-macOS / Cloud Agents

- Use an installed JDK 17.
- Ensure Java 17 is available before running Maven.
- Do not use the macOS `/usr/libexec/java_home` command on non-macOS systems.
- If Java 17 is unavailable, configure the minimum required JDK 17 environment before continuing.

## Verified ledgerwriter baseline

The following command has been successfully executed on the BankTO baseline:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./mvnw -pl src/ledger/ledgerwriter -Dtest=TransactionValidatorTest,LedgerWriterControllerTest test
```

Verified result:

- 21 tests passed
- 0 failures
- 0 errors
- 0 skipped

On a non-macOS environment, omit the macOS-specific `JAVA_HOME` command and run the same Maven Wrapper command after confirming JDK 17.

## Verified duplicate-request baseline

The following test has been successfully executed:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./mvnw -pl src/ledger/ledgerwriter -Dtest=LedgerWriterControllerTest#addTransactionWhenDuplicateUuidExceptionThrown test
```

Verified result:

- 1 test passed
- 0 failures
- 0 errors

Important:

This baseline only proves duplicate UUID rejection within a single controller/process instance.

It does not prove duplicate protection across:

- multiple ledgerwriter replicas
- multiple monolith instances
- process restart
- concurrent requests racing before cache insertion

Do not describe those scenarios as protected unless independently verified.

## Legacy monolith baseline

The following command has been successfully executed:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./mvnw -pl src/ledgermonolith test
```

Verified result:

- BUILD SUCCESS
- no automated tests discovered

Important:

ledgermonolith currently has no behavioral automated test suite.

A successful Maven test lifecycle therefore proves compilation, not payment behavior.

If a ticket changes ledgermonolith payment behavior, add appropriate behavioral regression coverage rather than treating compilation as sufficient verification.

## Testing strategy

- Run the narrowest relevant automated tests first.
- Run broader affected-module tests when practical.
- Payment behavior changes require regression coverage.
- Preserve existing test coverage.
- Never weaken, remove, skip, or alter a failing test merely to obtain success.
- Report exact commands and test results when engineering work is completed.

## Environment constraints

For normal BankTO interview engineering tasks, do not provision or deploy:

- Kubernetes
- GKE
- the complete Bank of Anthos environment
- Google Cloud infrastructure
- production PostgreSQL resources

unless the engineering ticket specifically requires them.

Prefer targeted local or Cloud Agent build and test workflows.

## Agent operating principle

Use repository evidence rather than assumptions.

If a required behavior cannot be verified with the currently available test infrastructure, explicitly identify that limitation and determine the smallest appropriate verification improvement.
