# Signal Lab functional design

Signal Lab demonstrates how Java, Rust, Python and React work together on a performance-sensitive workload. The [README](../README.md) defines the demo scope and gives the local run commands. [Acceptance status](AC.md) records the evidence and open gaps for the criteria below.

## Journey

1. The standalone fixture tool writes synthetic PCM WAV files and a versioned manifest. FastAPI reads them from a read-only mount and registers the manifest after applying its migrations. Registration is idempotent and readiness requires it to succeed. Each recording ID resolves to an asset in the catalogue.
2. React lists recordings and lets the user select files and channels. Spring validates the request and fetches recording metadata. It commits the batch and immutable input snapshots before publishing the batch ID to RabbitMQ.
3. A consumer claims the batch and processes items in request order. Spring streams each recording from FastAPI into a Rust child process. Rust validates and decodes the WAV, then processes complete windows with one, two or four workers.
4. Spring validates the result and commits it with the item's terminal state. React polls until the batch reaches a terminal state and shows per-channel RMS, spectrum, peak, band powers and any errors. The API can retrieve a stored batch by ID after a restart.

The [numerical profile](numerics.md) defines the supported input, FFT, band powers and reproducibility checks. The [shared contracts](contracts.md) define payloads, result provenance and the Java–Rust protocol.

## Components and ownership

| Component | Responsibility |
| --- | --- |
| React and nginx | Serve the UI and route same-origin API calls to Spring. Only the web port is exposed on the host loopback interface. |
| Java and Spring | Own the analysis API, queue orchestration, Rust process and the PostgreSQL `analysis` schema. Flyway manages its migrations. |
| Python and FastAPI | Own the `recordings` schema and serve the catalogue and recording bytes. Alembic manages its migrations. |
| Rust | Decode and analyze the binary input in a child process in the Spring container. |
| PostgreSQL | Store recording metadata, batch states and analysis results. Each service role can access only its own schema. |
| RabbitMQ | Deliver persistent batch messages and route malformed messages to a dead-letter queue. |
| Fixture and reference tools | Generate synthetic files and compare the engine output to the independent SciPy reference outside the stack. |

Services access each other’s data over HTTP. There are no cross-schema queries, foreign keys or transactions. The database stores input snapshots and aggregate results. Raw samples stay on the mounted filesystem, and computation happens outside database transactions.

## Failure and recovery

- **Invalid request.** Reject a malformed envelope or duplicate item IDs before creating a batch. An unknown recording or channel, unsupported or truncated file, digest mismatch or insufficient frames produces an item error. The remaining items can run.
- **Admission.** An accepted queued batch returns HTTP 202 after RabbitMQ confirms publication. A full queue returns 429 and leaves no batch. If every item fails initial resolution, persist the terminal result directly. A publication failure interrupts the batch.
- **Execution.** The conditional claim moves one batch from Queued to Running. Each consumer works on one batch at a time. A shared dependency failure interrupts the current item and marks pending items NotRun. Previously committed results stay available. A normal batch finishes as Completed, CompletedWithErrors or AllItemsFailed.
- **Acknowledgment and restart.** For a claimed batch, acknowledge after the terminal state is committed. If the broker redelivers a message for a batch still Running, mark that batch Interrupted. Messages for terminal batches do not execute again. At startup, republish old Queued batches in creation order until the queue is full. This covers a crash between the database commit and publication.
- **Process boundaries.** Spring starts the Rust process with an argument array, streams binary input with bounded buffers and drains output and diagnostics. It enforces deadlines and limits output size. Rust bounds memory by its window and buffer limits. Errors retain the batch and item identity where available. A browser disconnect can leave accepted work running.

See the [contract and protocol](contracts.md) for the complete state and validation rules. See [acceptance status](AC.md) for coverage and known recovery limits.

## Verification and delivery

The unit, integration and [browser tests](e2e.md) exercise real components with synthetic data. Static checks enforce the language and schema boundaries. Database roles, constraints, queue delivery and recovery have integration or end-to-end evidence. See the [quality workflow](../.github/workflows/quality.yml) and [acceptance status](AC.md) for the distinction between configured gates and observed results.

Compose is the primary local delivery target. Minikube and Helm use the same images as a second target with more than one application replica. Startup waits for service readiness. Normal shutdown preserves data. Each service owns its migrations, and applied migration files must remain immutable.

See [benchmarks](benchmarks.md) for the measured Rust runs, the estimated ceiling and the k6 workload. Timed runs require an agreed idle window. Compare worker counts with the same input and core placement. Record input size, toolchain, build, cache conditions, affinity, contention and repetitions. Report engine throughput separately from full-stack latency and include the limits of each measurement.

## Acceptance criteria

| ID | Required observable proof |
| --- | --- |
| SA1 | Compose starts the real stack and React lists the fixtures that the recordings service registered. |
| SA2 | A recording that the user selects in the UI goes through Spring, FastAPI and Rust. The UI shows a stored result. |
| SA3 | Zero, single-tone and multi-tone fixtures match the committed numerical and reference checks, within the stated tolerances. |
| SA4 | One valid recording and one unknown or corrupt recording give independent item outcomes in the same batch. |
| SA5 | A malformed envelope creates no analysis batch. The system identifies the invalid fields explicitly. |
| SA6 | A real dependency that stops, or a killed engine that the project owns, gives a visible infrastructure failure. It never gives a success. |
| SA7 | Completed results survive a restart. A batch that a dead consumer left Running is identified as interrupted when its message is redelivered. |
| SA8 | Real PostgreSQL constraints and transaction tests prevent a mismatch between a result and a terminal state. A service role cannot read or write the schema of another service. |
| SA9 | A full queue refuses a submission. The declared limits for buffering, time and processes apply. |
| SA10 | One, two and four workers give numerically equivalent results. They stay within the configured concurrency allocation. |
| SA11 | The static gate rejects representative type and nullability violations. The delivered code passes the gate. |
| SA12 | A fresh checkout can build, run all three test levels and stop the stack that it owns, with the documented commands. |
| SA13 | Performance evidence follows the reference protocol. It uses real measurements and no invented speedup. |
| SA14 | Real RabbitMQ in Docker. A submitted batch returns 202 and then reaches a terminal state. A stopped broker gives a visible failure and an Interrupted batch. A consumer that is killed before the acknowledgment does not cause two executions. A full queue returns 429. An unparseable message reaches the dead-letter queue. |
| SA15 | `helm install` on minikube starts all pods ready. The browser journey passes against the cluster. With two application replicas, both consume batches. A deleted application pod does not lose the queued batches. |

## Out of scope

The UI supports catalogue selection and the result of the current analysis. A history browser, arbitrary file uploads, configurable profiles, authentication, multitenancy and public hosting are outside the demo scope. Execution has no user cancellation, automatic retry or silent resume. Deployment across hosts and production qualification are outside the scope.
