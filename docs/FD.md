# Purpose and scope

**Version 1.1: functional design for a local Proof of Work.** The design has three main parts: asynchronous analysis through RabbitMQ, a second local target on Kubernetes (minikube), and measured performance.

A developer selects synthetic multichannel recordings and starts an analysis. The developer then inspects the stored numerical results and failures.

- The workload is real signal processing over binary samples.
- PostgreSQL stores metadata and results. SQL aggregation is not the numerical engine.
- The focus is Java, React, Python, Rust, performance, type safety, robustness, deployment and containers. Every language takes part in the same tested journey.

The polyglot split is deliberate demonstration scaffolding. Rust can implement the whole backend. This proof does not claim that four languages are the simplest production architecture. It demonstrates a functional design and controlled delivery. It is not a full production architecture exercise.

The application is for a trusted local environment.

- All application components run in Docker Compose. Kubernetes on minikube is a second local target for the same images.
- The public entry point is on the loopback interface only.
- These topics are excluded: authentication, login, authorization, multitenancy, public hosting, cloud infrastructure and distributed execution.

This design replaces the earlier quotation toy. The [engineering manifesto](engineering-manifesto.md) gives the engineering contract. This document gives the current functional scope.

# User journeys

## Prepare the catalogue

1. The developer runs the standalone fixture tool in `tools/fixtures`. The tool runs outside the Compose stack.
2. The tool generates real PCM recording files and a versioned manifest from explicit parameters and a seed.
3. The tool writes them to a host directory. The recordings service mounts this directory read-only.

Small fixtures are the default. Large data sets need a separate command and a declared storage budget. Startup and CI never generate gigabytes without a command.

When the recordings service starts, a bootstrap operation registers the immutable manifest in the schema of the service.

- The operation runs after the migrations. It is idempotent.
- The service reports ready on `/health` only after the registration succeeds.
- A failed registration stops the service.
- SQLAlchemy Core with psycopg 3 implements the data access. Alembic implements the migrations.
- A repeated registration for the same content identity must not create a conflicting catalogue entry.
- A recording identifier resolves only to a managed asset. It never resolves to a path or URL from the browser.

## Analyze recordings

1. The user opens React. The page shows the registered recordings with channel counts, sample rates and sizes. Spring reads them from the recordings service over HTTP.
2. The user selects recordings and channels, and submits the fixed analysis profile. There is no file upload and no JSON batch editor.
3. Spring validates the whole request envelope before it accepts work. Then it fetches the manifest of each selected recording from the recordings service.
4. Spring creates a stored batch with the state Queued and the item records, and commits them. Each item stores an immutable snapshot of its recording.
5. Spring publishes one message with the batch identifier to RabbitMQ. The request returns 202 with the batch identifier. See "Asynchronous analysis".
6. A consumer claims the batch. For each recording, the consumer gets the binary stream from FastAPI and passes it to the real Rust executable. Items run in sequence. Rust parallelizes the work inside the current item.
7. A successful item gives compact results. A failure that is specific to a recording gives an explicit item error. The other valid items continue.
8. React shows the states Queued and Running. It reads the batch every few seconds until the batch is terminal. Then the user can open a successful result or look at an item failure.

The diagnostics include the batch identifier after Spring creates it. The history view stays manual. There is no WebSocket and no polling framework.

## Review history

The user lists the previous batches and opens the stored results. The system does not compute them again.

- A result identifies the immutable input, the analysis profile, the engine version and the processing counts.
- A restart of the stack keeps the registered metadata and the completed results.

# Functional behavior and results

## Recording and numerical profile

The design uses a documented standard binary format: WAV with interleaved signed 16-bit PCM, one to four channels, 48 kHz. The supported input is narrow on purpose. The header, the declared frame count, the byte length and the manifest identity must agree.

The numerical profile:

- Convert each sample to a dimensionless value. Divide by 32768.
- Accumulate and analyze in f64.
- Process complete windows of 4096 frames for each selected channel. The windows do not overlap.
- Report the unused trailing frames. A recording with no complete window gets an item error.
- Report the unwindowed RMS over the processed frames.
- For the spectrum, use a periodic Hann window, no detrending, an FFT length equal to the window length, a one-sided density-scaled spectrum, and an arithmetic average across windows.

`contracts/v1/profile.json` has the authoritative values. This section describes the behavior. An independent [SciPy Welch reference](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.welch.html) checks the profile. The reference sets every option explicitly.

Each channel returns:

- its averaged spectrum
- the frequency of the dominant bin
- three band-power estimates

The bands are bin ranges: [0,128), [128,512) and [512,2049). The frequency labels come from the sample rate and the FFT length. Band power is the sum of the density values of the band, multiplied by the bin spacing. It is a windowed spectral estimate. It is not physical energy in joules, and it is not equal to the unwindowed RMS squared.

A zero spectrum returns an explicit NoPeak result. When two maximum bins are equal, the lowest frequency wins.

These items are not included: peak interpolation, arbitrary filters, an anomaly classifier, calibration to physical units and correlation between recordings.

## Results and reproducibility

Return one compact aggregate for each selected channel. Do not return one row for each window. React draws the spectrum in dark mode. It shows RMS, band powers, processed windows, ignored tail length and errors, with units.

A result records these values:

- the recording digest
- the fixture recipe version and the seed
- the selected channels
- the profile version
- the engine build identity
- the worker setting

Store the values that a replay needs.

Reproducibility means identical input bytes and numerically equivalent outputs, within the declared tolerances. The design does not promise bit-identical floating-point sums across execution profiles.

Before the implementation, commit these items:

- the exact normalization equations
- a runnable mathematical or reference check
- comparison tolerances for each metric

Check random inputs and DC, Nyquist, zero and boundary cases. Do not implement a formula that you derived by hand and verify it later.

# Component responsibilities and data

| Component                    | Responsibility                                                                                                                                                                                                                                              |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| React with strict TypeScript | Catalogue selection, typed request construction, in-progress and failure states, result history and an accessible spectrum display. It does not run the authoritative numerical analysis.                                                                   |
| Spring Boot with Java        | Public API, request validation, bounded orchestration, binary-stream forwarding, Rust lifecycle, result validation, and the publication and consumption of batch messages. It owns the `analysis` schema (batches, items, results) with its own migrations. |
| FastAPI with Python          | The recording catalogue. It owns the `recordings` schema with its own migrations. It registers and serves manifests, and streams the managed recording bytes from a read-only mount. It generates no data.                                                  |
| Fixture tool with Python     | Standalone, outside the stack. It generates deterministic data sets. It holds the independent SciPy numerical reference for small data, which runs the real Rust executable.                                                                                |
| Rust executable              | Real PCM decoding, bounded window processing, RMS and spectral analysis, deterministic result assembly, and explicit numerical or input errors.                                                                                                             |
| PostgreSQL                   | One instance with one schema and one login role for each service. Each role can use only its own schema. Integrity constraints protect each schema.                                                                                                         |
| Docker Compose               | Reproducible build, startup, networking, volumes, health checks and declared resource configuration.                                                                                                                                                        |
| RabbitMQ                     | The durable queue of batches that wait, and the dead-letter queue. It is the only message broker.                                                                                                                                                           |
| Kubernetes with Helm         | A second local target on minikube for the same images: web, application (more than one replica), recordings, PostgreSQL and RabbitMQ.                                                                                                                       |

## Storage and ownership

The services follow a strict microservice data rule:

- Each service owns its data. It is the only reader and the only writer of its schema.
- No table is shared between services.
- Access across services goes through the HTTP interface of the owner.
- There are no foreign keys, views or queries across schemas. Database roles enforce this.

The recordings service owns the Recording table. It holds the file identity and the format facts from the immutable asset manifest.

Spring owns AnalysisBatch and AnalysisItem.

- An item refers to its recording by identifier. It also holds an immutable snapshot of the manifest facts that it used. A result stays replayable if the catalogue changes.
- A mismatch between the file, the manifest and the snapshot is an error. The system does not update the metadata silently.
- A reference to a recording that the recordings service does not know is an item error. It is not a database constraint violation.

Raw samples stay on a read-only mount that the recordings service serves. They are not PostgreSQL large objects and not JSON arrays. FFT and sample processing stay in Rust.

Rules for the data model:

- An item has a closed state and exactly the payload that fits this state.
- Use database keys, uniqueness, checks and transaction boundaries inside each schema to protect its model.
- A transaction never spans services.
- Do not build a generic repository framework or a configurable workflow engine.
- Commit the terminal state of an item and its successful result together. A successful HTTP response cannot come before the persistence that it needs.
- Do not hold a database transaction open while the engine processes a large recording.

## Runtime shape

Five long-running containers run the stack: web, application, recordings, database and RabbitMQ.

- The application image contains the Spring service and the compiled Rust executable.
- Spring starts Rust directly with an argument array. It never uses shell interpolation.
- The web container serves the built React bundle. It forwards the same-origin API path to Spring.
- FastAPI and PostgreSQL stay on the internal Compose network.
- The Rust executable is not a service. It is a child process of Spring.

Spring forwards binary input to Rust with bounded buffers. Spring must do these tasks:

- Drain the result stream and the diagnostic stream correctly.
- Keep the exit status.
- Cancel or end the exact child process when a deadline passes or the service stops.
- Return only validated and bounded structured output.

## Asynchronous analysis

The analysis request is asynchronous. RabbitMQ is the only message broker. It is the fifth long-running container in Compose and a StatefulSet in the Kubernetes chart.

1. **Submit.** Spring validates the envelope. It creates the batch with the state Queued and commits it. Then it publishes one persistent message with the batch identifier to a durable queue, with publisher confirms.
   - If the broker does not confirm in the declared deadline, Spring sets the batch to Interrupted and returns an infrastructure error.
   - Otherwise the request returns 202 with the batch identifier.
2. **Claim.** Any application replica can consume.
   - A consumer claims the batch with one conditional update (Queued to Running).
   - A message for a batch that is not Queued is acknowledged and dropped.
   - A batch that a dead consumer left Running is set to Interrupted.
   - There is no silent resume and no automatic retry.
3. **Acknowledge.** The consumer acknowledges the message only after the batch reaches a terminal state and the result is committed.
   - A consumer that crashes before the acknowledgment causes a redelivery.
   - Delivery is at least once. The claim rule gives at most one execution.
4. **Dead letters.** A message that the consumer cannot parse goes to a dead-letter queue. The system never discards it without a trace.

The design does not claim exactly-once execution. Redis, Kafka and a distributed scheduler stay excluded.

# Failure and resource behavior

## Request and item outcomes

These errors reject the whole request before Spring creates a batch:

- malformed JSON
- missing required fields
- an unsupported profile shape
- duplicate item identifiers in the request
- an envelope that is larger than the declared limits

These errors give an item error. The other items stay eligible:

- an unknown recording
- an unavailable selected channel
- an invalid or truncated file
- a digest mismatch
- too few frames

A successful recording never contains a partial numerical result that looks complete.

A failure of a shared dependency or of the infrastructure is different. This applies to PostgreSQL, the message broker, the recordings service and the engine installation.

- Stop accepting work. Do not fail every remaining item again and again.
- Store an interrupted batch where possible.
- Never return global success.

A valid batch that ends normally gets one of these states, from its item outcomes: Completed, CompletedWithErrors or AllItemsFailed. An infrastructure interruption gives Interrupted.

The item transitions:

| From    | To          | Trigger                                                                                |
| ------- | ----------- | -------------------------------------------------------------------------------------- |
| Pending | Running     | The turn of the item begins and the shared dependencies that it needs are available.   |
| Running | Succeeded   | The numerical output and the input identity are verified, and the result is committed. |
| Running | Failed      | The system confirms a refusal that is specific to the recording, or invalid input.     |
| Running | Interrupted | Infrastructure, a deadline or a process shutdown prevents completion.                  |
| Pending | NotRun      | A shared failure aborts the rest of the batch.                                         |

Terminal items do not change state again.

The batch starts as Queued. A consumer claims it and sets it to Running. Items stay Pending until their turn. When every item is terminal:

- All items succeeded: Completed.
- Successes and item failures: CompletedWithErrors.
- All items failed: AllItemsFailed.
- An infrastructure abort: Interrupted, even if earlier items succeeded.

A batch can also go from Queued to Interrupted. This happens when the publication of its message fails at submission.

Keep the successful results that are already committed.

## Bounded execution

- The queue has a declared maximum depth. A submission that exceeds it returns `capacity_busy` (HTTP 429) and creates no batch.
- Each consumer runs one batch at a time. It processes the items in request order.
- Rust uses an explicitly sized pool of one, two or four workers. It never uses an unbounded pool for the whole host.

Typed configuration must define these limits: maximum batch items, input bytes, channel count, windows in flight, buffered bytes, result size, queue depth and operation deadlines.

- Enforce each limit at the boundary that is responsible for it.
- Memory use must depend on these limits and on the FFT size. It must not depend on the size of the whole recording.

The design does not include automatic retry, a fallback implementation, silent resume and user cancellation.

- A browser disconnect does not prove that the analysis stopped. Accepted work can continue until its server deadline. It stays visible in the history.

At startup, an application publishes again the batches that stayed Queued for longer than the declared age (`analysis.queue.reconcile-after`).

- This repairs a crash between the commit of a batch and the publication of its message.
- The claim drops a second message for the same batch, so several replicas can do this at the same time.
- Reconciliation stops when the queue is full. The next startup continues.
- An application never sets a Running batch to Interrupted at startup, because another replica can run it. The redelivery of the unacknowledged message does this.
- Do not claim recovery or exactly-once execution.
- If the database is not available, report the failure. Reconcile only when the application can start correctly.

Diagnostics name the batch, the item and the failing operation. They never contain raw recordings or secrets. Expected item refusals are typed outcomes. Unexpected process, protocol or persistence failures stay visible.

# Types and executable quality gates

Use one canonical public contract and one versioned Rust process contract. Use generated or mechanically checked models, and drift checks.

- Static types do not replace parsing at the boundary.
- Reject unknown fields and unsafe coercions at the boundaries that the project owns.

Model these values explicitly: identifiers, sample rates, frame counts, channel indexes, finite numerical results and state alternatives.

- Domain code has no null. Convert the absence that a framework or SQL forces on you to Option, Result or an equivalent sum type, in the adapter.
- Never send NaN or infinity as result data.

| Stack                    | Blocking checks                                                                                                                                                                               |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Java                     | Compiler warnings, Error Prone, NullAway with JSpecify, formatting, and exhaustive typed outcomes where the tools support it.                                                                 |
| Rust                     | Formatting, cargo check, Clippy with warnings denied, no unsafe in our crate, explicit errors, and no unchecked unwrap in application code.                                                   |
| Python                   | Pyright strict, Ruff checks and formatting, Pydantic strict models that forbid extra fields.                                                                                                  |
| React and TypeScript     | Strict compiler checks, exact optional properties, unchecked index checks, type-aware ESLint, promise handling, Hooks checks and accessibility checks.                                        |
| Contracts and repository | Schema and generated-code consistency, dependency locks, dependency and secret checks that apply, per-service SQL migrations (Flyway for Spring, Alembic for FastAPI) and Compose validation. |

- Pin compatible versions during the implementation.
- Do not add overlapping tools to increase their number.
- Each enabled check must protect a named failure mode, and it must run in CI.

Use a maintained FFT library through safe APIs. Forbid unsafe code in our own crate. This does not claim that all transitive dependencies are free of unsafe code.

A SIMD implementation must be identified and inspected. Do not invent one for the proof. To select the acceleration of a library is not the same as to demonstrate an optimization in our own data path.

# Real stack verification

**No mocks, fakes, stubs, spies, patched imports, intercepted requests or dependency overrides.** The tests run the real delivered components. Deterministic PCM files are data. They do not replace a service.

- Unit tests call pure production functions with ordinary values.
- Integration tests start the real PostgreSQL, the HTTP services and the Rust executable where the test claims these boundaries.
- Browser E2E tests use the built React application and the complete Docker stack that is used for delivery.

The conventions of the [end-to-end test guide](e2e.md) come from the gravity-pg testEnv guidelines:

- UI first for UI features.
- Typed clients only for scenarios without a page.
- One typed environment configuration.
- Canonical bootstrap identities.
- One worker for the shared UI journey.
- No test retries and no arbitrary sleeps.
- Name the journey dependencies that are on purpose, and start from a known data set.
- Standalone negative scenarios own their data and their lifecycle.

The independent SciPy computation is a numerical oracle on small real files. It does not replace Rust in the application. Commit analytic and reference checks before you implement a non-trivial mathematical transformation. Use random and boundary inputs, and examples that you can check by hand.

| AC   | Required observable proof                                                                                                                                                                                                                                                                                                       |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SA1  | Compose starts the real stack and React lists the fixtures that the recordings service registered.                                                                                                                                                                                                                              |
| SA2  | A recording that the user selects in the UI goes through Spring, FastAPI and Rust. The UI shows a stored result.                                                                                                                                                                                                                |
| SA3  | Zero, single-tone and multi-tone fixtures match the committed numerical and reference checks, within the stated tolerances.                                                                                                                                                                                                     |
| SA4  | One valid recording and one unknown or corrupt recording give independent item outcomes in the same batch.                                                                                                                                                                                                                      |
| SA5  | A malformed envelope creates no analysis batch. The system identifies the invalid fields explicitly.                                                                                                                                                                                                                            |
| SA6  | A real dependency that stops, or a killed engine that the project owns, gives a visible infrastructure failure. It never gives a success.                                                                                                                                                                                       |
| SA7  | Completed results survive a restart. A batch that a dead consumer left Running is identified as interrupted when its message is redelivered.                                                                                                                                                                                    |
| SA8  | Real PostgreSQL constraints and transaction tests prevent a mismatch between a result and a terminal state. A service role cannot read or write the schema of another service.                                                                                                                                                  |
| SA9  | A full queue refuses a submission. The declared limits for buffering, time and processes apply.                                                                                                                                                                                                                                 |
| SA10 | One, two and four workers give numerically equivalent results. They stay within the configured concurrency allocation.                                                                                                                                                                                                          |
| SA11 | The static gate rejects representative type and nullability violations. The delivered code passes the gate.                                                                                                                                                                                                                     |
| SA12 | A fresh checkout can build, run all three test levels and stop the stack that it owns, with the documented commands.                                                                                                                                                                                                            |
| SA13 | Performance evidence follows the reference protocol. It uses real measurements and no invented speedup.                                                                                                                                                                                                                         |
| SA14 | Real RabbitMQ in Docker. A submitted batch returns 202 and then reaches a terminal state. A stopped broker gives a visible failure and an Interrupted batch. A consumer that is killed before the acknowledgment does not cause two executions. A full queue returns 429. An unparseable message reaches the dead-letter queue. |
| SA15 | `helm install` on minikube starts all pods ready. The browser journey passes against the cluster. With two application replicas, both consume batches. A deleted application pod does not lose the queued batches.                                                                                                              |

- Corrupt files and dependency shutdowns are created only in test resources that the project owns.
- The tests keep the complete failure output and the exit codes.
- A required check that is blocked means that the proof is not complete.

# Performance protocol

## Workload and comparison

Use small fixtures for correctness. Use separately generated recording sets of GB scale for throughput and memory behavior. Record the exact bytes and digests.

Do not start any large fixture generation, calibration, profiling or load benchmark until the owner agrees that the reference machine is idle.

Benchmark the real processing algorithm, not a busy loop.

- Keep an idiomatic scalar, library-backed implementation as the reference.
- Then measure buffer reuse, layout, bounded batching and an explicitly identified accelerated FFT profile.
- Do not compare against a baseline that is poor on purpose.
- Do not call a library speedup our own new FFT algorithm.

A suitable maintained library has an explicit [scalar FFT planner](https://docs.rs/rustfft/latest/rustfft/struct.FftPlannerScalar.html) and a [planner for accelerated algorithms](https://docs.rs/rustfft/latest/rustfft/struct.FftPlanner.html). Select and pin the implementation later. If the system cannot supply the acceleration that the run requests, the run must fail explicitly. It must not select a different benchmark profile silently.

- Record the compiler output or the library kernel evidence, and the instruction set in use.
- Attribute each improvement to a measured change.
- Compare the scalar and the accelerated modes with one worker. Then compare the same mode with one, two and four workers.

## Tools and reports

Run the performance protocol with two tools:

- The Rust crate uses criterion for the kernel throughput (bytes per second and samples per second). It also uses criterion for worker scaling with one, two and four workers.
- A k6 script drives the full stack. It measures batch latency (p50, p95, p99), throughput and error rate.

The results and the machine description go to `docs/benchmarks.md`. CI runs only a tiny benchmark smoke check.

## Reference machine and resources

Use the machine of the owner and four separate cores of the same CPU class.

- Check the topology and the allowed affinity again before each run.
- A CPU quota is not CPU affinity.
- The first discovery found a cpu_core group with CPUs 0–7. The first candidate allocation is CPUs 0–3. The run must verify it.

Use the same shared set of four cores for all measured application processes, including PostgreSQL and FastAPI. Do not give each container its own set of four cores.

- State where the browser and the load generator run.
- Keep the workers, the service threads and the memory limits explicit.
- Report the contention that you observe inside the allocation.

Run kernel experiments and full-stack experiments separately.

- For the kernel, define the interval, state if decoding is included, and state the cache regime.
- For the whole stack, include streaming, digest verification, Rust startup and PostgreSQL persistence.
- Do not claim kernel throughput as application throughput.

Report these values:

- samples per second and input bytes per second
- recording latency and the p95 and p99 values that apply
- memory use, error rate and variability

Record the data size, the compiler and image identity, the warm-up, the repetitions, the frequency and the host activity. Compare one, two and four workers with the same workload. Separate the algorithm improvement from the parallel speedup.

## Estimated upper envelope

Include an optimistic ceiling model for the real mix of operations. Use consistent units.

- Execution ceiling: the relevant operations available per second, divided by the operations for each processed sample.
- Transfer ceiling: the bytes available per second, divided by the bytes moved for each sample.
- Use the smaller value and explain the assumptions.

The FFT operation count depends on the window size and on the algorithm. Identify the count and its limits from the kernel that the project implements. A SIMD width or an arbitrary FLOP estimate is not enough. Separate cache traffic, DRAM traffic and end-to-end I/O.

State which inputs come from documentation, assembly inspection, independent calibration or assumptions. Give a range where the uncertainty matters.

- Report the proximity to the estimated ceiling. Do not report CPU utilization or a certified hardware limit.
- A measurement above the estimate needs an investigation of the model and of the measurement scope.

Rules for tolerances and targets:

- Justify the numerical comparison tolerances with the reference checks before you implement the numerical transformations.
- Fix the numeric performance targets after the first baseline and before you evaluate an optimization.
- All timing claims stay unverified until the agreed idle-machine runs.
- CI runs the correctness checks and a tiny benchmark smoke check. It does not enforce timing thresholds on noisy shared runners.

# Docker delivery and acceptance

Deliver one repository with these items: locked builds, versioned migrations, fixture recipes, the fixed numerical profile, typed contracts, all test levels and Docker Compose.

The README contains only these sections: minimal getting started, how to run tests, how to build/deploy. It links to this FD and to the companion evidence.

Build the real React bundle, Java artifact, Rust binary and Python environment.

- Use maintained base images. Use a multistage build where it removes build tools from the runtime images.
- Pin the dependencies and the image identities.
- Runtime users are unprivileged. Credentials are configuration. Only the local web port is published.

Storage and shutdown:

- Use a PostgreSQL volume and a read-only recordings mount with explicit ownership.
- A normal shutdown keeps the data.
- A destructive reset is a separate operation with a clear name. It affects only the resources of this project.
- Never mount the Docker socket into an application container.

Startup and readiness:

- Each service applies its own versioned migrations to its own schema at startup. It verifies the real readiness with bounded waits.
- A database initialization script creates one schema and one role for each service. Each service has its own credentials as configuration.
- Compose supports [health-dependent startup](https://docs.docker.com/compose/how-tos/startup-order/). A created container is not an application that is ready.
- Resource profiles use the documented [Compose service settings](https://docs.docker.com/reference/compose-file/services/), with real CPU placement where it is required.

Deploy means: build and run the local Docker stack from a clean checkout, verify it, and demonstrate data persistence and shutdown. It does not mean a cloud rollout, a public endpoint, high availability or production qualification.

## Kubernetes target

A Helm chart in `deploy/helm/signal-lab` deploys web, application, recordings, PostgreSQL and RabbitMQ to a local minikube cluster.

- The script `deploy/minikube.sh` uses its own minikube profile. It passes the context to every command, so it never touches another cluster.
- The application runs as a Deployment with more than one replica.
- Each pod has resource requests and limits, a readiness probe and a liveness probe.
- Credentials come from Kubernetes Secrets.
- Compose stays the primary local stack. Kubernetes is a second target for the same images.
- This is not a cloud rollout, not high availability and not production qualification.

## Continuous integration

CI builds the same runtime artifacts, runs the static gate and runs real unit, integration and browser E2E checks.

- Keep the reports, the traces and the complete failing output for the exact commit.
- A configuration file or a workflow that did not run is not a passing result.

The handover identifies the source commit, the image identities, the successful commands, the real CI run, the numerical evidence, the performance evidence when it exists and the remaining limits. Publishing the repository or submitting a candidature needs separate authorization.

# Scope limits and implementation decisions

## Explicit exclusions

These items are excluded: login, authentication, authorization, a tenant model, user management, arbitrary uploads, cloud provisioning, Redis, Kafka, a distributed scheduler, a retry framework, automatic resume, user cancellation, real-time acquisition, a machine-learning model and a custom FFT.

The design does not claim that the service split is a recommended production design. It excludes a full domain-driven design exercise, a generic platform, a plugin framework and a business product. The functional design, the failure semantics, the types, the tests, the reproducibility and the deployment stay in scope.

PostgreSQL is a real dependency for persistence and integrity. It does not duplicate the signal processor. The four language roles demonstrate the target skills. They do not justify an enterprise architecture.

## Decisions to close before coding

- Pin compatible runtime, library and analyzer versions.
- Commit the numerical equations, the reference checks, the fixture recipe and the tolerances for each metric before you implement the mathematics.
- Commit the exact request, result and process schemas, the encoding of the state-transition table, and the finite resource limits.
- Confirm the value of every limit and deadline in the typed configuration, including the queue depth. Spring uses provisional deadlines for its calls to the recordings service: 2 s to connect and 10 s to read (`application.properties`).
- Define the engine build identity so that a result can be replayed. A version label alone is not enough.
- Define the integration test level that SA12 needs, or reduce SA12 to the levels that exist.
- Agree the development time budget. Large data sets also need an explicit storage budget.
- Agree an idle-machine window before any performance run. Set numeric performance targets after the first baseline.

This is a complete functional scope with explicit implementation gates. It is not an implemented or verified application. Do not fill an unresolved numerical or resource limit with an undocumented magic constant.
