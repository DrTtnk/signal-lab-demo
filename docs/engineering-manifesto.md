# Definition and purpose

**Toy problem. Complete engineering.**

A **Proof of Work (PoW)** is a small Git repository that works. It demonstrates the skills that a target assignment needs. The business problem is small on purpose. The engineering standard is complete.

A PoW shows how we do five things: define work, design software, implement it, verify behavior and deliver a reproducible result. It is evidence of a demonstrated capability. It does not show production experience, operational scale or client adoption.

This is our engineering policy, version 1.1. **MUST** marks a completion requirement.

## Scope and required stack

1. Choose one user journey. Choose the smallest domain that uses the required technologies.
2. Give each technology a real role. An unused dependency or a diagram does not demonstrate a skill.
3. Before coding, record these items: the target requirements, the scope, the exclusions, the acceptance criteria (AC), the work steps and the time budget.
4. Map each required skill to its code path and its verification.

These items are mandatory, also when the client does not ask for them: functional design, AC, unit tests, integration tests, end-to-end (E2E) tests and CI. A small domain keeps them affordable.

When the time budget ends, reduce the features and agree a smaller scope. Do not remove required tests. Do not weaken the completion criteria. Reuse the same proof across suitable assignments.

## Engineering rules

- **Functional design.** Keep domain decisions in pure, composable functions. Use immutable data. Keep I/O and state changes at explicit boundaries.
- **Type safety.** Parse external input into domain types. Model absence and expected failure with Option, Result or an equivalent sum type. Do not use null in domain code. Convert the nulls that the language or a third party forces on you at the boundary.
- **Strict behavior.** Validate the required configuration at startup. Fail fast and visibly on invalid input, broken contracts or missing dependencies. An expected refusal is an explicit domain outcome. Never turn an unexpected failure into success.
- **One authority for each fact.** Derive values. Do not keep duplicate state. Share the schema, the configuration and the verification logic through their real source of truth.
- **Simplicity.** Choose the simplest design that meets the requirements. Before you add complexity, name the simpler alternative and the concrete failure that it cannot handle. Use maintained libraries for costly or complex behavior. Infer local types where the language permits it.
- **No hidden recovery.** Do not swallow errors. Do not add silent fallbacks or repeated defensive checks in the domain. Keep logging minimal and free of secrets.
- **Proof over claims.** Each rule that a machine can check needs an automated check. Non-trivial mathematics needs tool verification before the implementation. Commit a reproducible check.

## Acceptance criteria and verification

Write observable AC before the implementation. Include the successful journey, invalid input, the refusal paths that apply and the state invariants. Each AC must name a test or a specific manual check.

All three test levels MUST exist:

| Level       | What it proves                                                                    |
| ----------- | --------------------------------------------------------------------------------- |
| Unit        | Pure domain decisions, edge cases and explicit outcomes.                          |
| Integration | Real boundaries: the database engine, the schema, HTTP or the protocols you need. |
| E2E         | The complete journey through the built application and its real dependencies.     |

If a feature has a UI, its E2E test MUST use the UI. A backend-only proof can start at its public API, CLI or protocol. It must verify the complete resulting behavior. Do not add a UI only to create an E2E test.

Use real services and synthetic data. A protocol-compliant toy server proves its implemented protocol. It does not prove compatibility with a different vendor. A claim about a vendor needs the supported test environment of that vendor.

**No mocks of any kind.** Do not use fakes, stubs, spies, patched modules, dependency overrides or intercepted requests to replace a component under test.

- Pure functions receive ordinary input values.
- Integration and E2E tests run the real delivered components.
- A blocked required check stays blocked. A substitute cannot complete it.

A negative claim needs a forbidden action that the test attempts and a refusal that the test observes. Do not write tests only to mirror the implementation or to increase coverage. Do not retry failed tests until they pass. Do not add sleeps or longer timeouts to hide defects.

## Delivery process and AI use

Work in small steps that a reviewer can check:

1. Define the problem, the AC and the minimum stack.
2. Split the journey into bounded tasks. Record short design decisions.
3. Apply RED–GREEN–REFACTOR. Observe a meaningful failing test. Implement the minimum change. Then refactor while the checks pass.
4. Review the diff. Run the full required suite and inspect the result. Render and inspect visual output at its final size.
5. Record the evidence against the exact commit. Prepare the handover.

AI can help with planning, implementation, tests, review and documentation. The engineer must understand and explain the result, challenge the assumptions that the AI generates and own the delivery. Generated code, passing mocks and the success message of an agent are not verification.

- Record mistaken assumptions in `useful_knowledge.md`.
- Agree scope changes explicitly.
- Give short written updates: completed, next step, blocker.
- Measure the actual effort, the elapsed delivery time and the rework. Do not assume a productivity multiplier.

## Repository and CI contract

The repository MUST contain:

- The problem, the scope, the AC and short design decisions, in issues or in a small document.
- Working source, dependency locks, the configuration examples that are required and a reproducible test setup.
- Unit, integration and E2E suites with meaningful assertions.
- A CI pipeline. The pipeline installs the locked dependencies, checks formatting and types, builds, runs all three suites and keeps the evidence of failures.
- A README with only three sections: minimal getting started, how to run tests, and how to build/deploy. Put the problem brief and the design notes in other files.

Local checks and CI checks must exercise the same contracts. E2E must exercise the built artifact. A missing service, a skipped mandatory suite and a failed command must fail the pipeline. A workflow file that never ran is not a passing CI.

Use synthetic data and code that we can publish. Include no client code, no credentials and no confidential material.

## Definition of done

A PoW is ready to share only when all these statements are true:

- Its required technologies run in the demonstrated journey. Optional technologies are included only when the agreed proof needs them.
- Each AC has evidence. All three test levels and the blocking CI pass on the referenced commit.
- A fresh checkout follows the documented setup without an undocumented manual repair.
- The engineer reviewed the code and inspected the real result.
- The Git link, the commit and the CI run identify what was verified.
- The scope and the limits are explicit. No exception for test doubles is permitted.

A blocked requirement makes the proof incomplete. A green pipeline proves only the behavior that it checks. A video can help the review. It does not replace runnable evidence. Publication needs the approval of the owner.

## Example

**Target skills:** TypeScript, PostgreSQL, RLS and MCP.

**Toy problem:** two tenants create and read short notes. A minimal MCP tool exposes the same application operation as the CLI.

- **AC-1:** a tenant can create and read its own note.
- **AC-2:** an attempt to read the note of another tenant is refused.
- **AC-3:** missing identity or invalid configuration is refused.
- **AC-4:** the CLI and MCP give the same defined outcome.

The tests:

- Unit tests cover the domain decisions and the decoding.
- Integration tests send allowed and forbidden operations to a real PostgreSQL with RLS.
- E2E tests start the built application and call its real CLI and MCP interface.
- CI runs all of them.

Exclude billing, dashboards, deployment clusters and other features that do not belong. The domain stays a toy. The delivery demonstrates the complete method.

## The extended engineering contract

**Version 1.1: designed for senior engineers with little patience.**

This contract extends every requirement above.

- MUST means required.
- SHOULD means expected. A deviation needs a short, specific reason.
- A conditional rule applies when its trigger exists. Do not add a database, service, UI, cloud account or mathematical model to activate a rule.

The reviewer needs no private knowledge of the repository. The setup, the intent, the failures and the evidence must be visible. A small proof can meet a strict contract with few files and few tools.

### 1. Define behavior before structure

Functional design has two meanings here: specify what the system does, and structure the domain logic as pure functions. Neither replaces the other.

The brief MUST identify these items:

- the actor
- the input and the observable output
- the allowed state changes
- the refusal conditions
- the invariants and the exclusions

Distinguish four cases: a business refusal, malformed input, a dependency failure and an internal defect. Define the order, the handling of duplicates and the repeat execution when they change the result.

- Give the AC stable identifiers.
- Connect each AC to an executable check and its evidence.
- Use concrete examples. "Robust", "clean", "fast" and "production-ready" are not AC.
- Keep a short task list. Each task has a completion condition.
- Record only the decisions that change a boundary, a dependency, a contract or the verification strategy. State the simpler option and why it is not enough.
- A diagram must explain a real boundary. It must not invent components.

**Acceptance check:** another engineer can predict an allowed result and a refused result before they read the implementation.

### 2. Make a fresh checkout a supported environment

The repository MUST state the supported runtime, compiler and package-manager versions.

- Commit the dependency locks and the configuration examples that are required.
- Do not use floating `latest` tags for a dependency that establishes reproducibility.
- Record the tested platform. Do not claim support for an operating system that you did not check.

Use the standard tools of the ecosystem. Provide one obvious entry point for setup, run, tests, build and cleanup. A small task file can coordinate existing tools. Do not build a task framework. The help output must list the commands, the prerequisites and the destructive effects.

Setup MUST find a missing prerequisite early, name it and exit with a failure. Setup must not do any of these actions:

- install global tools silently
- change a shell profile
- need administrator rights that the documentation does not state
- change another project

A configuration example must not contain usable credentials.

- Separate the required values from the safe development defaults.
- Parse the configuration once into typed values. A missing required value is an error.
- Document the ports and the external accounts.
- Allocate isolated resources for concurrent runs. Do not assume that the host is empty.

**Acceptance check:** a fresh checkout completes the documented setup and the full verification without an undocumented repair.

### 3. Optimize the feedback path

Provide separate commands for fast unit tests, integration tests, E2E tests and the complete verification. A developer must be able to run one test without starting unrelated services. Watch mode is useful when the standard runner supports it. Do not create a custom watcher.

Use the same build and test commands locally and in CI. CI can add scheduling, caches and artifact upload. It must not use different application semantics.

Measure the real setup time and the test duration before you claim fast feedback. Record the machine and the cache conditions when you compare timings. The contract sets no universal time target. Improve a step that you measured as slow before you add another layer of orchestration.

Formatting and linting rules MUST be executable and consistent. Keep only the rules that protect correctness, readability or repository consistency. A formatter settles formatting disputes.

**Acceptance check:** the contributor can find the smallest useful check and can run the complete gate.

### 4. Treat contracts as executable boundaries

Use domain types for identifiers, quantities, states and validated input where they prevent a real error.

- Prefer closed alternatives to loosely related flags.
- Make illegal state combinations unrepresentable where the language permits it.
- Use type inference for local values. Keep explicit public contracts where they improve correctness and review.

Static types do not validate network input, files, environment variables or another process. Decode and validate once at each trust boundary. Inside the boundary, use the validated type. Do not repeat speculative checks in the domain.

Define the wire formats, the status codes, the error categories, the numeric ranges, the encodings and the compatibility expectations. A cast is not a parser. Do not keep parallel definitions of one contract by hand. Use a canonical schema and maintained tools when a duplicate in several languages can drift.

If a schema or an API changes, define the compatibility, the migration and the rollback before you apply the change. A rollback claim needs a tested way back. Label irreversible changes as irreversible.

**Acceptance check:** malformed input at a boundary cannot enter a valid domain state. A contract change breaks a relevant check.

### 5. Keep code small enough to inspect

- Use descriptive names, short operations that hold together, and explicit dependencies.
- Keep business decisions separate from transport, persistence and framework setup.
- Prefer composition and ordinary data to inheritance trees and hidden mutable state.
- Add an interface only when a real boundary or a substitution requirement exists. Do not create an interface, factory, repository layer or plugin system for every class. A possible future is not a requirement.
- Comments explain intent, constraints and choices that are not obvious. Delete comments that repeat the code.
- Remove dead code, unused options, abandoned experiments and dependencies that you do not need.
- Use maintained libraries for complex parsing, cryptography, protocols and other behavior where an error is costly. Do not add a dependency for a trivial operation when you can inspect its full behavior and edge cases. Record the trade-off if it is material.

**Acceptance check:** you can trace the main journey from the public entry point to the domain outcome without unnecessary indirection.

### 6. Test decisions, boundaries and the delivered system

RED–GREEN–REFACTOR MUST show a meaningful failure for the behavior under development. A test that fails because its setup is broken does not establish the red step. A fix for a defect needs a regression test that fails for the defect.

- Unit tests do not use the network or a database.
- Integration tests MUST cross the real boundary that their claim names.
- E2E tests MUST use the built application. They must not use a special implementation with different behavior.

Rules for UI and E2E tests:

- Use page objects for UI actions. Use typed clients for backend-only checks.
- Read all service addresses from one typed environment configuration. Read all generated identities from the bootstrap result. Do not hardcode test URLs, credentials or generated IDs in single tests.
- Create, assert and clean up through the supported product surface. An API shortcut must not replace a UI action when the UI provides that action.

Rules for test data and order:

- Give tests their own resources and a reproducible starting state.
- Unit tests and standalone scenarios must not depend on a previous test.
- A sequential E2E journey can carry state between steps on purpose. The dependency must be explicit. The whole journey must start from a clean bootstrap. Do not run a shared-state journey in parallel.
- Pass time, random seeds and identifiers as explicit domain input where needed. Do not replace an integration with a fake. Record the random seeds when they matter.
- Test invariants with generated inputs when this is more useful than a few selected examples.

Rules for waiting and failures:

- Use observable readiness conditions with a bounded deadline. Do not use arbitrary sleeps.
- A failed assertion stays failed. Diagnose flaky tests. Do not hide them behind retries.
- A skipped mandatory suite makes the verification incomplete.

Where it applies, test cancellation, duplicate input, delivery out of order, transaction failure and partial failure. Do not claim exactly-once execution, idempotency or atomicity unless you define and exercise the boundary that gives it.

A coverage report can show missing cases. A percentage cannot establish correctness. A test must check an outcome that is stated independently. It must not reproduce the implementation.

**Acceptance check:** the suite detects a deliberate fault that is relevant. Repeated runs do not inherit hidden state.

### 7. Make failure useful

Fail fast at the boundary that is responsible. Return an explicit, typed refusal for an expected domain outcome. Pass unexpected failures up with enough context to identify the failing operation. Never return empty data or success to hide an error.

A useful diagnostic states the operation, what failed and the corrective action that is available. Keep the original cause. Do not log the same error at every layer. Do not add a logging platform to a toy.

CLI tools MUST separate machine-readable output from diagnostics. They MUST use documented exit codes. Do not mix progress text into a JSON stream. Tests and CI must keep the exit status and the decisive output of the failing command.

For long output, save the complete output and inspect it. Do not pipe the first and only view through `head`, `tail` or `grep`. Never let a successful last command of a pipeline hide an earlier failure.

**Acceptance check:** an intentional configuration or dependency failure gives a non-success result and a useful diagnostic without secrets.

### 8. Own process and resource lifecycles

Each resource that the proof creates needs an owner and a cleanup path. This includes child processes, containers, temporary directories, database records and listening ports.

- Use exact process identifiers or named job units. Do not find or kill processes by matching command-line text.
- Do not write wait loops without a bound. Use the completion mechanism of the runner, and bounded waits where needed.
- Handle normal termination and cancellation.
- Document each resource that stays on purpose after a run.
- Cleanup must affect only the resources that the run owns. A general system prune is not a project cleanup command.

Set explicit deadlines and input limits at real external boundaries, where an untrusted peer can otherwise hold or exhaust resources. These are contract requirements. They are not speculative defensive checks. This proof has no fallback and no retry mechanism. Define the failure outcome directly and keep it.

**Acceptance check:** after you stop a test or the application, no owned process or resource stays without an explanation.

### 9. Treat security and data integrity as behavior

- Use synthetic data.
- Keep secrets out of the source, the logs, the screenshots, the test reports and the generated artifacts.
- An example configuration must be safe to publish.
- Record the trust model and the deployment assumptions of the proof.

If identity, permissions or tenant isolation are in scope, test the allowed operation and the forbidden operation that you attempt. Use the real enforcement boundary. Administrative credentials must not replace an application role.

For data and arithmetic:

- Parameterize data access.
- Where persistence exists, define the transaction boundaries and the constraints.
- Use integer units or a suitable decimal representation for exact quantities.
- Test the range and the overflow behavior when arithmetic can cross a boundary.

Use dependency and secret checks that fit the stack. Review the findings. Do not suppress them silently. Pin CI actions and restrict workflow permissions. Untrusted contributions must not receive deployment secrets.

Do not build authentication, encryption or audit infrastructure only to claim security. A local proof can exclude a hostile-network deployment explicitly. It must not then be called secure for that environment.

**Acceptance check:** each claim about security or integrity has a matching test and a stated scope.

### 10. Measure performance without theater

A toy can show a measurement method. It cannot establish production scale because it uses a fast language.

If performance is part of the claim, define these items: the operation, the data set, the build mode, the machine, the warm-up, the repetitions and the metric. Keep a baseline and a reproducible command. Report distributions or variability where it is material. Do not present the best run as the typical result.

Verify that the outputs are equivalent before you compare implementations. Include the cross-process and network costs when the claim is about end-to-end performance. State the limits of the sample.

Do not add concurrency, caching, queues or a distributed deployment without a concrete requirement. If concurrency exists, define the ownership, the order, the backpressure and the cancellation. Check the race and overload behavior that applies.

**Acceptance check:** another engineer can repeat the measurement and understand what it establishes and what it does not.

### 11. Make UI behavior and accessibility testable

This section applies when a UI exists.

- Use semantic controls, accessible names, keyboard navigation and visible focus.
- Show loading, success, empty and failure states explicitly.
- Keep the user input when a recoverable operation fails.

Test the real user journey in the browser. Check the rendered UI at its intended size. Inspect the labels, the layout, overlaps and the presentation of errors. Accessibility checks support the review. They do not replace keyboard and visual inspection.

Keep the presentation minimal and consistent. Do not build a design system for one form. Use dark mode for our visualizations. Do not show implementation detail in text for the user, unless it helps the user decide or recover.

**Acceptance check:** a reviewer can complete the main journey with the keyboard. A reviewer can understand a refused operation without logs.

### 12. Make CI a release witness

CI MUST start from a clean checkout. It MUST install the locked dependencies, check formatting and types, build, and run unit, integration and E2E tests. A missing service, a missing suite, a failed command or a skipped requirement that you did not expect must block the gate.

- Caches can reduce the time. A run with a cold cache must still work.
- Declare the cache inputs. Do not reuse outputs when you do not know their source identity.
- Keep the required steps executable outside the hosting platform.
- Keep useful failure artifacts: the full command output, the test reports and the traces that apply. Remove secrets from them. Give the artifacts a commit identity.
- A badge without a matching run is not evidence.

Separate validation from deployment. Do not deploy, publish a package, expose a service or change an account only because tests passed. These actions need their own authorization.

**Acceptance check:** a reviewer can follow the commit to its real CI run and inspect the checks that passed.

### 13. Make Git history and handover reviewable

Use bounded changes with messages that explain the intent. Review the complete diff, including lockfiles, configuration, generated files and deletions. Do not mix unrelated cleanup into a proof.

On master, pull the latest changes before you start work. Stop for conflicts that you did not resolve. Keep the changes and the active work of other contributors. Do not rewrite shared history without agreement.

The README MUST stay current. It contains only minimal getting started, how to run tests, and how to build/deploy. Put the brief, the functional design, the AC mapping, the design decisions and the limits in companion files with clear names. Link them where a reviewer needs them. Do not duplicate facts across documents.

A handover states what works, the verified commit, the exact verification commands, the links to the evidence, the limits and the next useful step. It separates work that is completed, active, blocked and unverified.

**Acceptance check:** a new engineer can find the main code path, run the checks and understand the remaining limits without a meeting.

### 14. Give AI the same engineering constraints

An AI task needs a bounded objective, AC, the context that applies, the allowed changes and a stop condition. Inspect the repository before you propose replacements. Keep the existing conventions unless a concrete defect needs a change.

- Use small tasks and review their results.
- Parallel work needs explicit ownership boundaries. Do not let several agents edit the same area silently.
- The output of an agent is a proposal until you check its diff and its behavior.

The engineer owns each dependency, command, permission request and external action. Do not run generated commands blindly. Keep private code and credentials inside the tools and the destinations that are authorized.

The agreement of a second model is not independent evidence. A generated test can share the mistake of the implementation. Challenge both with stated contracts, real dependencies, negative tests and observed results.

Record a mistaken assumption and the corrected evidence in `useful_knowledge.md`. Measure the real delivery outcomes. Never infer commercial experience or an AI productivity multiplier from a successful toy.

**Acceptance check:** the engineer can explain the result, reproduce its evidence and identify the parts that stay unverified.

### 15. Apply a strict completion gate

Before you share the proof, verify these statements:

- The brief names the target skills, the smallest journey, the exclusions and the time budget.
- The functional behavior, the functional code design and the AC are explicit.
- Each technology that you include has a necessary role in the demonstration.
- The runtime boundaries and the wire boundaries reject malformed input with explicit outcomes.
- Unit tests, real integration tests and E2E tests of the built application exist and pass.
- The complete CI gate passes for the referenced commit.
- You exercised fresh-checkout setup, local verification and cleanup.
- A relevant failure gives a diagnostic and keeps the failing exit status.
- You rendered and inspected UI or other visual output where it exists.
- Claims about security, performance and reliability have matching evidence.
- The README, the design notes and the handover describe the delivered state.
- Secrets, confidential material and unrelated features are absent.
- The limits are visible. No exception for test doubles is permitted.
- The owner approved the publication.

**A blocked required check means incomplete. A small scope is acceptable. Unsupported claims are not.**

### 16. Reviewer route

Give an impatient reviewer a short route:

1. Read the brief and the AC.
2. Follow the main code path.
3. Run the smallest example.
4. Run the full verification.
5. Inspect the exact CI run and the limits.

The opening explanation states the problem, the design choice and the evidence. Let the repository prove the rest.

Do not ask the reviewer to admire the number of technologies, files, tests or agents. Make the behavior easy to inspect and difficult to misrepresent.

### 17. Static checks are part of the design

Use a committed quality profile for each stack. Run it on production code, tests and the build tools that the project owns.

- Warnings from enabled checks block CI.
- Pin the tool versions.
- Record a narrow and justified suppression at its source. Do not turn off a category in the whole repository to make the build green.
- Do not use `any`, an unchecked cast, an ignore directive, a non-null assertion or an unchecked unwrap to silence a real modeling problem.
- A boundary of a framework or of generated code needs explicit adapters and a reviewed scope. Do not pretend that a nullable API of a framework does not exist.

| Stack                    | Required quality profile                                                                                                                                                                                                                                                                                                           |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Java and Spring          | Compiler warnings as errors. Error Prone and NullAway with JSpecify. Non-null domain contracts. Formatter and unused-dependency checks. Use records and sealed outcomes where they express the model. Convert the nulls of the framework at adapters.                                                                              |
| Rust                     | `cargo fmt` check. `cargo check`. Clippy with warnings denied for all targets. Exhaustive outcomes and checked arithmetic. Forbid unsafe code in our crate. Deny unchecked `unwrap` and `expect` in application code. Use explicit assertions in tests. Review the pedantic lints. Do not enable incompatible rule groups blindly. |
| Python and FastAPI       | A strict static type checker with a pinned version. This project selects Pyright. Ruff lint and formatting. Typed public boundaries and Pydantic strict validation that rejects unknown fields. No untyped application functions. No silent coercion of input.                                                                     |
| TypeScript and React     | TypeScript strict mode, `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`. Type-aware ESLint for unsafe operations, promises and exhaustive state handling. React Hooks and accessibility rules. Formatter. Model UI states as discriminated unions.                                                                     |
| Cross-language contracts | A canonical wire contract. Clients and models that are generated or mechanically checked. Schema validation and checks for drift after regeneration. Verify the same accepted and rejected payloads at the real boundaries.                                                                                                        |
| Repository               | Lockfile consistency. Secret and dependency checks. Checks of the commands in the documentation. Forbidden dependencies and imports for test doubles. Validation of the CI workflow that fits the chosen tools.                                                                                                                    |

Select the smallest maintained set of tools that covers distinct failure modes. Do not add two overlapping Python type checkers or three Java analyzers unless you name the extra defect class that they detect. Static checks add to the runtime evidence. They do not replace it.

The documentation of the tools for the selected stacks:

- [Spring null safety](https://docs.spring.io/spring-framework/reference/core/null-safety.html)
- [NullAway JSpecify support](https://github.com/uber/NullAway/wiki/JSpecify-Support)
- [Clippy usage](https://doc.rust-lang.org/clippy/usage.html)
- [Pyright configuration](https://github.com/microsoft/pyright/blob/main/docs/configuration.md)
- [Pydantic strict mode](https://docs.pydantic.dev/latest/concepts/strict_mode/)
- [TypeScript compiler options](https://www.typescriptlang.org/tsconfig/)

Select the exact runtime and plugin versions together. Check them in the implementation.

**Acceptance check:** representative violations of nullability, unsafe types and unhandled outcomes fail the relevant configured checks. Record these calibration results. A configuration file is not proof that an analyzer runs.
