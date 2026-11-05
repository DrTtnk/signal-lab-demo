# Acceptance and implementation status

[FD.md](FD.md) is the approved design. It is the authority for product behavior. [Shared contracts](contracts.md) and the [numerical profile](numerics.md) define the version 1 boundary.

The first analysis journey works: select, analyze, show, store. The history view is not implemented.

Validation on 4 November 2026: 199 distinct tests and all static checks passed with cache bypass; all four build tasks passed. The isolated Compose suite passed all 17 E2E tests. The two demo volumes retained their identity and creation metadata after cleanup. No new timed benchmark was run.

## Summary

| AC       | State   | Subject                               |
| -------- | ------- | ------------------------------------- |
| SA1      | Done    | Recording catalogue in the browser    |
| SA2      | Done    | First analysis journey                |
| SA3      | Done    | Rust core against the SciPy reference |
| SA4      | Partial | Mixed valid and invalid items         |
| SA5      | Partial | Request validation                    |
| SA6      | Partial | Infrastructure failures               |
| SA7      | Partial | Restart and recovery                  |
| SA8      | Partial | Database constraints                  |
| SA9–SA10 | Partial | Limits and workers                    |
| SA11     | Partial | Quality gates                         |
| SA12     | Partial | Setup, check and build                |
| SA13     | Partial | Performance measurement               |
| SA14     | Done    | Asynchronous batch queue              |
| SA15     | Done    | Kubernetes deployment                 |

## SA1: Recording catalogue (Done)

- The browser test lists the five registered fixtures through nginx, Spring, FastAPI and PostgreSQL.
- `npm run e2e` passes end to end with 17 tests: the UI journey, a message broker outage, a recordings service outage and an application restart. See the [end-to-end test guide](e2e.md).

## SA2: First analysis journey (Done)

- The browser test selects a recording and analyzes it through Spring, FastAPI and the Rust executable.
- The page shows RMS, peak, band powers and the spectrum.
- The test reads the stored batch back through the API.

## SA3: Rust core against the SciPy reference (Done)

- The Rust core matches the independent SciPy reference in `tools/fixtures`. This covers fixtures, seeded random inputs and edge cases, within the canonical tolerances.
- A deliberate change to an interior weight in the Rust code makes these tests fail.
- Two and four workers give exactly the same result as one worker. The tests use random files with 1, 2 and 4 channels. Reversing the summation order makes them fail.

## SA4: Mixed valid and invalid items (Partial)

Done:

- An API test mixes an unknown recording with a valid one. The batch state is CompletedWithErrors.
- An API test checks an unavailable channel. The batch state is AllItemsFailed.
- The engine runner test covers a corrupt recording.

Not done: a browser test.

## SA5: Request validation (Partial)

Done:

- An API test sends an unknown field. The API returns 400, names the field and creates no batch.
- An API test sends a duplicate item identifier. The result is the same.

Not done: a browser test.

## SA6: Infrastructure failures (Partial)

Done:

- An unavailable engine interrupts the batch. The batch is stored as Interrupted and the API returns 503.
- A stopped recordings service and a frozen recordings service give a 503 InfrastructureError.
- The WAV request has a response-header timeout from `spring.http.clients.read-timeout` (10 s by default). A Java test pauses the real service and checks `HttpTimeoutException`. After headers, the engine deadline bounds processing of the streamed body.
- Black-box e2e (`resilience`): with the message broker stopped, the page shows an interrupted batch with `broker_unavailable`. With the recordings service stopped, the page shows `recordings_unavailable`. Both recover.
- The broker connection timeout is 2 s. A Java test with a blackholed address covers it.

Not done:

- A killed engine in the middle of a run. This includes the delivered stack.
- A stopped PostgreSQL. This includes the delivered stack.

## SA7: Restart and recovery (Partial)

Done:

- `RestartTest` uses the real stack. Completed results stay the same after the application restarts.
- A batch left Queued without a message (crash between commit and publish) is published again at startup. It must be older than `analysis.queue.reconcile-after` (2 min). The batch then completes.
- A fresh Queued batch is not published again.
- A batch left Running by a dead consumer is interrupted when its message is redelivered (`BatchQueueTest`).
- Black-box e2e (`resilience/restart`): a completed batch is still Completed after the application container restarts.

Kubernetes evidence:

- The same orphan case completed on minikube after a rollout restart.
- Three hard kills (`--grace-period=0 --force`) of one application pod under k6 load left no batch stuck or interrupted. All 1448 batches completed.
- A batch takes about 10 ms, so these kills probably missed every batch in flight. The cluster test does not show the interrupt path.

Not done:

- A history view (a list of batches).
- A Running batch whose message went to the dead-letter queue stays Running.

## SA8: Database constraints (Partial)

- Nine real PostgreSQL constraint tests check item states, batch finish times, positions, foreign keys and the snapshot rule. They assert the exact SQLSTATE.
- Each service role cannot read the schema of the other service. The tests check both directions.

## SA9–SA10: Limits and workers (Partial)

SA9:

- A full queue refuses a submission with 429 `capacity_busy` and creates no batch.
- The engine runner test checks the process deadline with an open input pipe.
- Not tested: the buffering limits.

SA10:

- One, two and four workers give bit-identical results (Python tests with the real engine).
- The worker setting reaches the engine and is stored (Java test).
- The pool size is exactly `workers` by construction.
- Not measured: thread count and CPU allocation.

## SA11: Quality gates (Partial)

- These gates pass: frontend, Python, Java (Error Prone, NullAway) and Rust (Clippy).
- The Rust input adapter and the Java application use the canonical schema at runtime.
- The frontend batch parser validates the canonical JSON Schema with Ajv, including batch/item state rules and recording provenance. Regression tests reject contradictory terminal states.
- The catalogue and error parsers still use hand-written Zod definitions.

## SA12: Setup, check and build (Partial)

- `npm run setup`, `npm run check` and `npm run build` pass.
- The real Compose scaffold starts healthy, and the browser smoke test of the built UI passes.
- Compose E2E uses a separate project, port, fixture directory and volumes. Cleanup is limited to that project.
- A clean copy of the local source passed setup, Compose startup and shutdown, browser flow, all three test levels and the build on 5 November 2026.
- A clean checkout of the published revision and hosted CI for these changes remain unverified.

## SA13: Performance measurement (Partial)

Done (see [benchmarks](benchmarks.md)):

- One criterion run on CPUs 0–3. The kernel takes 20.3 µs for each window.
- For a 33.5 MB file, 1, 2 and 4 workers take 275, 223 and 178 ms. After the bulk decoder they take 143, 93 and 55 ms.
- An upper-envelope model puts the engine at 18% to 24% of the four-core ceiling. The engine is execution-bound. Two inputs of the model are assumptions.

Limits:

- The machine was not fully idle.
- No measurement was repeated.

Not done: the k6 timing run, repeated runs and a memory measurement.

## SA14: Asynchronous batch queue (Done)

`BatchQueueTest` uses real RabbitMQ, PostgreSQL, the recordings service and the Rust engine. It shows these results:

- A submitted batch returns 202 and reaches a final state.
- A stopped broker gives 503 and an Interrupted batch with `broker_unavailable`.
- A full queue gives 429 `capacity_busy` and creates no batch.
- A batch whose consumer died before the acknowledgment is interrupted on redelivery. It never runs twice. A change to this rule makes the test fail.
- A message for a finished batch is dropped.
- An unparseable message reaches the dead-letter queue.

`AnalysisQueueTest` runs eight concurrent consumers. Exactly one consumer claims the batch.

Limit: a broker connection can drop while a live consumer still works. The batch becomes Interrupted although it can finish.

## SA15: Kubernetes deployment (Done)

- The chart deploys web, application (2 replicas), recordings, PostgreSQL and RabbitMQ on the minikube profile `signal-lab`. All pods become ready.
- The browser e2e passes through a port-forward to the cluster.
- A 60-iteration k6 run was consumed by both replicas. `claimed_by` shows 35 and 26 batches.
- A pod was deleted during a 20 s k6 run. All 645 batches completed. None stayed Queued, Running or Interrupted.
- The upgrade from the synchronous release kept the 1461 existing batches (migration V2).
