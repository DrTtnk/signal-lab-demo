# Black-box end-to-end tests

The suite in `tests/e2e` runs on the delivered stack. It uses the real containers, which start from a clean state. The conventions come from the gravity-pg testEnv guidelines of this team, reduced to what this project needs.

- `npm run e2e` runs the whole suite on Docker Compose.
- `deploy/minikube.sh e2e` runs the `flow` project on the minikube cluster.

## Rules

1. **No mocks.** Every request reaches a real service. A test never replaces a module, intercepts a request or patches an import.
2. **UI first.** If a feature has a page, the test uses the page. A test can call the API only for an action that has no page. The list is in "Steps that do not use a page".
3. **One worker, no retries, tests in order.** The tests tell one story on one shared stack. A test can depend on the earlier tests of its file. A flaky test is a bug. Fix it.
4. **Isolated clean state.** `tests/e2e/run.sh` uses project `signal-lab-e2e`, port 4175 and `tests/e2e/compose.env`. It does not read the demo's `.env`.
   1. Remove only that project's containers and volumes.
   2. Write the five fixtures to `.work/e2e-fixtures`.
   3. Build the images (`docker/build.sh`).
   4. Start the test stack and wait for health.
   5. Run the tests.
   6. Remove the test containers, networks and volumes on exit, including failure.

   The `setup` project checks that exactly the five fixtures are registered. Resilience commands use the same explicit Compose project. The demo's `signal-lab` containers, volumes and `.fixtures` are separate. Image tags are shared; a build does not replace a running demo container.

5. **One typed environment.**
   - URLs, paths and the Compose project name come from `src/env.ts`.
   - Every HTTP call goes through `src/api-client.ts`, which asserts the exact status code.
   - Shared IDs come from `src/test-data.ts`, which the `setup` project writes.
   - Nothing is hardcoded.
6. **No sleeps.** A wait is a condition: a visible element, a response, or `expect.poll` on a real signal such as container health.
   - The default test timeout is 15 s. The assertion timeout and the navigation timeout are 5 s.
   - A test that needs more time states the reason. The specs that stop and start containers use 60 s for the Docker calls.
   - Do not raise a timeout to hide slowness. Find the cause.
7. **No hidden errors.** A test has no `try`/`catch`, no conditional, no `force`, and no skipped or focused test.
8. **Test names** have the form `{number} — {what the test does}`. The number is the execution order in the file.
9. **ESLint enforces these rules** (`tests/e2e/eslint.config.js`):
   - `eslint-plugin-playwright` rules for focused, skipped, conditional, forced and timeout-based tests.
   - `no-restricted-syntax` rules for `.ok()`, a raw `fetch`, a hardcoded URL, `page.goto` in a test body and a wrong test name.

   A deliberately bad spec proves that the rules work.

## Projects

| Project      | Files                                    | Purpose                                                                                                                                                                    |
| ------------ | ---------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `setup`      | `src/global.setup.ts`                    | Reads the catalogue, checks the clean state and writes the shared test data.                                                                                               |
| `flow`       | `src/signal-lab/signal-lab-flow.spec.ts` | The UI journey: catalogue, mono, stereo with tail, two recordings in one batch, stored batch.                                                                              |
| `resilience` | `src/signal-lab/resilience/*.spec.ts`    | Stops or restarts a real container of the Compose stack: message broker outage, recordings service outage, application restart. Compose only. The cluster target skips it. |

## Steps that do not use a page

| Where                                                         | Step                                                   | Reason                                                                             |
| ------------------------------------------------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| `signal-lab-flow.spec.ts` test 5 and `restart.spec.ts` test 3 | `GET /api/analyses/{id}`                               | No page lists or opens a stored batch. The history view does not exist.            |
| `src/stack.ts`                                                | `docker compose stop`, `restart` and `up` of one stack | The scenario injects this fault. A stopped container is not the system under test. |

## Coverage of the acceptance criteria

| AC                                     | Black-box e2e                                                                                  | Other tests                                                      |
| -------------------------------------- | ---------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| SA1 catalogue in the UI                | `flow` test 1                                                                                  |                                                                  |
| SA2 analysis in the UI, stored         | `flow` tests 2 to 5                                                                            | k6 smoke, Java `AnalysisApiTest`                                 |
| SA4 valid and failing item in a batch  | Not possible. The UI offers only registered recordings and their real channels.                | Java `AnalysisApiTest` (unknown recording, unavailable channel)  |
| SA5 malformed envelope                 | No, by rule. The page never sends one.                                                         | Java `AnalysisApiTest`                                           |
| SA6 stopped dependency shows a failure | `resilience`: message broker and recordings service. Not covered: PostgreSQL, a killed engine. | Java `BatchQueueTest`, `EngineRunnerTest`                        |
| SA7 restart                            | `resilience/restart.spec.ts`: the completed batch survives an application restart.             | Java `RestartTest` (orphan batch), `BatchQueueTest` (redelivery) |
| SA9 capacity                           | No. The queue cannot be full on the delivered stack without a test hook.                       | Java `BatchQueueTest` (429)                                      |
| SA15 Kubernetes                        | `flow` project through `deploy/minikube.sh e2e`                                                |                                                                  |

## Not done

- Visual regression snapshots.
- A code-coverage dump of the running services.
- Tests of the history view. The view does not exist.
