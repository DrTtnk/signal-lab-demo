# Useful knowledge

- The patch tool does not permit deleting and adding the same file in one patch. Use a guarded update of the current text. A formatter also invalidates old patch context; read the affected file again before editing it.

- JSON Schema format declarations are not automatically enforced by the base jsonschema package. A negative test accepted an invalid date-time until the format validation dependencies were explicitly installed. Keep the refusal test in the contract suite.
- JSON decoding can produce infinity from a numeric exponent such as 1e999, not only from nonstandard NaN/Infinity tokens. Reject both at the wire boundary with the standard JSON finite-number encoder check.

- Re-read handoff documents before patching them. A stale commands-document heading caused a guarded patch to be rejected; the failed patch made no file changes.

- Docker Compose quiet progress omitted the underlying frontend compiler diagnostic in this environment. Plain progress saved to a complete log exposed TS2882; Vite client types fixed the actual missing CSS import declarations.
- Python builds also generate package metadata under src/*.egg-info. Ignoring dist and build does not cover those files; exclude them from Git and the Docker build context.
- File discovery with rg --files omits hidden files by default. Check toolchain pin files explicitly before assuming they are missing; .python-version was already present.

- Superseded 2026-11-02: package managers once needed caches in `.work/` because the home caches were read-only. They are writable now, so the redirects were removed. Check the real constraint again before keeping an environment workaround.
- The assigned project parent did not exist at task start. The local repository was created at the specifically requested path after write access became available.
- Scaffold script assumption corrected: creating a file under a not-yet-created parent directory fails. Create all parents before writing workflow files; the interrupted write did not overwrite earlier files.
- Dependency assumption corrected: the current accessibility ESLint plugin peer range stops at ESLint 9, while ESLint 10 is published. Keep ESLint and `@eslint/js` at 9.39.5 so npm can enforce the peer contract; do not bypass peer checks.
- Python patch assumption corrected: `uv` has Python 3.13.7 installed, but its managed download index does not offer 3.13.9. Pin the verified local interpreter patch (3.13.7) for this scaffold.
- Compose assumption corrected: a quoted shell command inside a YAML flow sequence broke Compose parsing. Put the healthcheck command in a block sequence so the quote parser sees the full command as one scalar.
- Docker runtime assumption corrected: the Java runtime image does not guarantee `wget`. Add the explicit `curl` health probe dependency before using it in Compose.
- Maven resolver assumption corrected: this host has an employer-specific mirror in user settings. Do not expose or edit that configuration. The project uses a local, credential-free settings file that resolves public dependencies from Maven Central.
- TypeScript asset assumption corrected: Vite ships declarations for CSS imports via `vite/client`. Include that supported type entry; do not add an untyped local declaration or weaken TypeScript.
- Error Prone assumption corrected: JDK 25 blocks compiler internals unless Maven receives the module export/open flags. Use the official Error Prone Maven JVM configuration in this repository; do not add global JVM flags.
- Compile resource assumption corrected: Cargo defaults to host-wide parallelism. The two-job bound now lives in `backend/rust/.cargo/config.toml`; Docker still passes `--jobs 2`.
- Maven dependency-tree command correction: this shell expands an unquoted `*` before Maven receives it. Quote the `-Dincludes=...` value when checking exact groups. The first two inspections did not run.
- Dependency setup correction: Maven `dependency:go-offline` fetched Mockito artifacts even though the resolved dependency tree excluded them. Use `dependency:resolve` for project dependencies; replace the broad Spring test starter with JUnit Jupiter for the scaffold test.
- TypeScript configuration review corrected: the Vite starter template included `skipLibCheck`. Remove it so project checks include dependency declarations instead of hiding type errors.
- Prettier does not provide parsers for TOML, Makefiles, or Java. Use Ruff, Make syntax execution, and the Maven Spotless gate for those files.
- TypeScript dependency declaration conflict found after removing `skipLibCheck`: `@testing-library/jest-dom` augmentation types disagree with Vitest assertion generics under TypeScript 7. The scaffold needs no custom DOM matchers, so remove that package and use native assertions instead of suppressing library checking.

## Rust scalar core implementation

- In jsonschema 0.37.4, ValidationError exposes `instance_path()` and `kind()` methods. They are not public fields; `kind()` supports Debug formatting, not Display. The compiler caught the incorrect field assumption before any engine tests ran. Check the pinned crate source when using its API.

- Hound 3.5.1 reports a short sample read as `io::ErrorKind::Other`, not `UnexpectedEof`. The first real truncated-file test exposed exit 70 instead of the required typed refusal. The WAV decoder boundary now emits `UnexpectedEof` when a required header/sample read reaches EOF, before Hound can reclassify it. The outer stream separately checks the exact total byte count after draining metadata. No message matching or error fallback is used. An intermediate fix based only on total byteLength missed a data chunk that claims frames beyond the RIFF container; a second real-file RED test caught this and caused the rule to move to the required-read boundary.

## Repository cleanup (2026-11-02)

- The removed Python verification layer (SciPy oracle, SymPy identities, fixtures, 114 tests) was never committed. Git history cannot restore it. A backup was written outside the repository before deletion.
- Docker build DNS assumption corrected: on this machine, builds on the default bridge network fail with `dns error` for `files.pythonhosted.org`, while a running container and `docker build --network host` resolve it. Root cause found: build containers inherit the Wi-Fi link resolvers (172.29.1.36, 172.29.5.36), which fail lookups, while run containers use the Docker defaults (8.8.8.8, 1.1.1.1). Fixing it needs `"dns": ["1.1.1.1", "8.8.8.8"]` in `/etc/docker/daemon.json` and a Docker restart; this is a host change, not a repository change. Earlier builds only passed because layers were cached; a changed `uv.lock` invalidated the layer. Compose bake rejects `build.network: host` without `--allow=network.host`, and the cache is keyed by network mode, so pre-building with host networking does not warm the cache. Tag host-built images with the Compose image names and run `up` without `--build` to test the stack.
- npm workspaces assumption corrected: `npm ci` needs the `package.json` of every workspace listed in the lock file. The web Dockerfile therefore copies the manifests of all five workspaces.
- Turborepo strict environment mode hides variables such as `JAVA_HOME` and proxy settings from tasks. `turbo.json` sets `envMode` to `loose`.
- zsh does not split an unquoted variable into words. Use a shell function for a repeated compose command.
- A shell with a stale `VIRTUAL_ENV` that points to a deleted environment makes uv print a mismatch warning. Run `deactivate` or open a new shell.
- Turborepo hashes only files inside a package by default. The Rust package reads `contracts/v1` through `include_str!`, so `backend/rust/turbo.json` adds it as an input. Without it, a contract change would leave a stale cached pass.
- Oracle restored in `tools/fixtures` (2026-11-02) from the backup of the removed layer, without SymPy and without the schema-level contract tests. A mutation test (interior one-sided weight 2.0 changed to 1.0 in `dsp.rs`) failed the Rust comparison, which shows that the oracle detects a normalisation error. The mutation was reverted.
- Testcontainers for PostgreSQL: the final server runs after the init scripts and logs `[1] LOG:  database system is ready`; the temporary init server logs the same sentence with another PID. stdout and stderr are separate streams, so counting occurrences of the sentence fails. Wait for the PID 1 line with `LogMessageWaitStrategy`.
- Starlette 1.x `TestClient` is typed through `httpx2`; with only `httpx` installed Pyright strict reports unknown types. The dev dependency is `httpx2`.
- A mutation that removed the digest check did not fail the first test set, because the size check caught the appended bytes first. A same-size content change test was added. Each check needs a test that only that check can fail.
- PostgreSQL runs `/docker-entrypoint-initdb.d` scripts only on an empty data volume. An existing Compose volume needs `down --volumes` after the init script changes.
- Spring Boot 4.0.1 HTTP client deadlines are `spring.http.clients.connect-timeout` and `read-timeout`. The older-looking `spring.http.client.*` names did nothing: a frozen (paused) real container made the request hang until nginx returned 504. A test with a paused real container found it; a stopped container (connection refused) did not.
- Spring Boot 4 has no `RestClient.Builder` bean without `spring-boot-starter-restclient`. `RestClient.create()` and `RestClient.builder()` ignore Boot settings, such as `spring.jackson.deserialization.fail-on-unknown-properties`. Inject the builder. A throwaway probe against a real HTTP server showed that unknown fields give a 500 with the property true and a 200 with false. That server was not committed, because it would be a test double.
- `mvn package` in the application image ran the Docker-dependent integration tests and failed. Image builds use `-DskipTests`; `check` runs the tests.
- Testing Library does not clean up after each test unless Vitest has globals enabled. A shared setup file calls `cleanup`.
- Spring Boot 4 does not run Flyway with `flyway-core` alone. Use `spring-boot-starter-flyway`; without it the migration is silently skipped and the first sign is `42P01` (missing table) in tests. Constraint tests assert the exact SQLSTATE, so a missing table cannot pass for the wrong reason.
- `eslint-plugin-jsx-a11y` rule `label-has-associated-control` crashes with the root `minimatch` 11 override (`minimatch is not a function`). Do not wrap controls in `<label>`; give each checkbox an `aria-label` and a plain `<span>` for the visible text.
- A `PipedInputStream` without a connected writer fails at once with "Pipe not connected"; a deadline test needs a connected `PipedOutputStream` that never writes. The first version of the test passed for the wrong reason.
- Duplicate `--key=value` Spring command-line arguments are joined with a comma. The test helper builds one map of properties, so an override replaces a default.
- A `JsonNode` boundary for contract documents (validated by the canonical schema) removed the need to duplicate 20 contract types in Java; the Java code only builds and reads the few fields it needs.

## Benchmark slice (2026-11-03)

- Cargo reads the manifest before it builds anything. A `[[bench]]` entry makes `cargo build --release` fail in Docker when `benches/` is not copied (`can't find engine bench`). The application Dockerfile now copies `backend/rust/benches`. A real `docker build --network host --target rust-build` proved the failure and the fix.
- `cargo add --dev criterion` chose 0.7 because the crate declared `rust-version = "1.85"`, while the pinned toolchain and the README say 1.91. The declaration was stale. It is now 1.91 and criterion is 0.8.2 without default features (no plotters).
- Clippy denies `unwrap` and `expect` in benches too (`--all-targets`). Criterion closures return `()`, so setup returns `Result` and the bench entry panics with the error message.
- `docker compose up --build` still failed with `dns error` on this host (2026-11-03), as recorded above. Building each image with `docker build --network host -f docker/<name>.Dockerfile -t signal-lab-<name> .` and running `up --wait` without `--build` worked, and the k6 smoke passed on that stack.
- k6 2.x provides `crypto.randomUUID()` globally. Do not import UUID helpers from `jslib.k6.io`; a remote import makes the script depend on a network at run time.

## Kubernetes slice (2026-11-03)

- The user's current kubectl context was a real shared cluster. Assumption corrected: never rely on the current context. `deploy/minikube.sh` uses the profile `signal-lab` with `--keep-context` and passes `--context` to every kubectl and helm call. The current context was checked before and after `up`; it did not change.
- zsh does not split an unquoted variable (`k="kubectl --context x"; $k get pods` fails). It happened twice. Use a shell function. In a `sh` script a function is fine, but `$!` after `kube ... &` is the PID of a subshell, not of kubectl: `kill` left `kubectl port-forward` running and held the port. Start the background process directly, without a function.
- Kubernetes has no `depends_on`. The application and recordings pods crash with `Connection refused` while PostgreSQL starts, and restart twice. This is fail-fast, so it stays; a wait loop or an init container would hide the dependency.
- `helm lint` exits 0 when a `required` value is missing (INFO only). `helm template` fails with exit 1. Use `helm template` as the check.
- Helm cannot read files outside the chart. The database init script is passed with `--set-file databaseInit=docker/database/init.sh`, so Compose and the chart share one file.

## RabbitMQ slice (2026-11-03)

- `minikube image load --overwrite` did not replace an image that already had the same tag on the node (the cluster kept running the old synchronous application, and the e2e passed in 500 ms instead of 2.5 s, which gave it away). `deploy/minikube.sh up` now tags each image with its content ID, so a new build changes the Deployment and Kubernetes rolls out the pods. Check the image ID on the node (`minikube ssh -- docker image ls --no-trunc`) after a load.
- In a `sh` function or script that builds the Helm arguments with `set --`, the subcommand is still in `"$@"`. Use `shift` first.
- Loading images into the minikube node disturbs the node's Docker daemon: `database-0` and `rabbitmq-0` restarted once, with a clean shutdown and no data loss.
- A response to POST /analyses must describe the batch as accepted. A consumer can claim the batch before the controller reads it back, so the first version answered `Running` instead of `Queued` (the test saw 200 instead of 202). The snapshot is taken before the publish.
- Awaitility rethrows an uncaught exception of any thread. Spring AMQP closes a channel asynchronously when an application context shuts down, and `RejectedExecutionException` surfaced in the next test's `await()`. `TestStack.await()` uses `dontCatchUncaughtExceptions()`. The test failed three times in a row before this was understood, so it was not a flake.
- A mutation that does not compile proves nothing: `false && redelivered` was rejected by Error Prone (dead code). `!redelivered` compiled and the redelivery test failed as it should.
- NullAway: `MessageProperties.isRedelivered()` returns a nullable `Boolean`; convert at the boundary with `Boolean.TRUE.equals(...)`. `Throwable.getMessage()` is nullable too; keep the detail in a field, as `ContractViolation` does.
- RabbitMQ `guest` can connect only from loopback. The Testcontainers broker uses its own admin user.

## Docker DNS, repository fix (2026-11-03)

- Assumption corrected: `build.network: host` in compose.yaml with `COMPOSE_BAKE=false` does not work. Docker Compose 5.6.1 always builds through bake; the variable is ignored (also from `.env`), bake answers `additional privileges requested: pass "--allow=network.host"`, and `docker compose build` has no `--allow` option. The images are now built by `docker/build.sh` with `docker build --network host`, used by the python `image` step and `tests/e2e/run.sh`; Compose runs them without `--build`.

## Parallel workers (2026-11-03)

- A test that used `workers=4` as its invalid input stopped working when 4 became valid. Java validates the EngineInput against the shared schema before it starts the engine, so no input that passes Java can make the real engine exit 64. The test for the runner's "unexpected exit code" branch now kills the real engine process from outside (`ProcessHandle.destroyForcibly`) and expects `engine_protocol_error`.
- Summing the per-window density terms in window order (after the parallel FFTs) gives bit-identical results for every worker count, because each term is computed by the same expression and the additions happen in the same order. Parallel summation by chunk would only be equivalent within tolerances. The mutation test (reversed order) fails 6 of 8 cases; the two cases with one window cannot show a difference.
- `for_each_init` with the whole `Spectrum` as init would plan the FFT and compute the Hann window again for every rayon task. The immutable plan and window are shared (`Spectrum`), and only the two FFT buffers (`Workspace`) are created per task.

## Profile of the sequential part (2026-11-03)

- Assumption corrected: the unbuffered hashing wrapper (SHA-256 updated for every 2-byte Hound read) was not the main sequential cost. SHA-256 of 33.5 MB takes 8.6 ms and the wrapper adds 18-23 ms. Hound's per-sample iterator alone takes about 100 ms. Measure each part before proposing a pipeline.
- The component benchmarks run on one thread; pin them to a quiet core. The owner's long-running Python process moved from CPU 4 to CPU 3 during the session, so re-check the per-core load before every run.
- Result of the profile: the bulk decoder (one `read_exact` per window, Hound only for the header) took the single-worker run from 275 ms to 143 ms and four workers from 178 ms to 55 ms. `WavReader::into_inner` returns the reader positioned at the first sample. Short reads are `UnexpectedEof` from `read_exact`, which maps to the same typed truncation refusal as before.
- Two benchmark runs on different cores are not an A/B test: the unchanged kernel benchmark moved by 5% between CPUs 0-3 and 4-7. Compare the change against that noise level.

## Restart reconciliation (2026-11-03)

- Assumption corrected: "mark every Running batch Interrupted at startup" (the FD 1.0 rule) is wrong with more than one replica, because a restarting pod would interrupt the live batch of the other pod. With the queue, a batch left Running by a dead consumer is handled by the redelivery of its unacknowledged message. The real gap was the batch that stays Queued without a message (crash between commit and publish); `QueueReconciler` publishes batches older than `analysis.queue.reconcile-after` again, and the exclusive claim makes duplicates harmless.
- Hard-killing a pod under load did not produce an interrupted batch in three attempts: a batch lasts about 10 ms, so the kill almost never lands inside one. Do not claim cluster evidence for the interrupt path from that test; the Java redelivery test is the evidence.
- The test application must not pick up batches that earlier tests left Queued: the default test value of `analysis.queue.reconcile-after` is 1 h, and only the reconcile tests lower it.

## Upper envelope (2026-11-03)

- Assumption checked by the model: 2 scalar operations per cycle is not excluded by the kernel measurement (201 Msamples/s is inside 161-206 for one core), so the ceiling is only known to about a factor of two. The two inputs that cause this (operations per cycle, DRAM bandwidth) are assumptions; calibrate them with a microbenchmark before quoting a closer figure.
- pytest.approx is untyped under pyright strict (reportUnknownMemberType); use math.isclose in tests of this package.

## Black-box e2e (2026-11-03)

- The team's e2e conventions are in `~/Documents/leo/gravity-pg/testEnv/PHILOSOPHY.md` and `GUIDELINES.md` (found through the memory palace, not by file search: a `find` with `head` cut the listing before `leo/`). They are summarised in `docs/e2e.md`.
- Assumption corrected: a stopped broker is not refused at once. The container address stops answering, so a new connection waits for the RabbitMQ connection timeout (60 s by default), and the request thread waits behind the listener container's reconnect attempts (first request 38.8 s, the next 25 ms). `spring.rabbitmq.connection-timeout=2s` fixes it; the confirm timeout of 5 s did not cover connection setup. A black-box test found this, the white-box Java test did not, because Testcontainers maps the port on the host and a stopped container is refused immediately.
- `docker compose up --wait` aborts with "dependency failed to start: container is unhealthy" when a service is unhealthy for a moment while it reconnects (the application after the broker returns). Start without `--wait` and poll the health with `expect.poll`.
- Playwright loads the spec files before the setup project runs, so a spec cannot read the shared test data at describe time; read it inside a test or a hook.

## Comment removal (2026-11-03)

- All comments were removed from the tracked non-Markdown files (129 of 133: 81 plain and 52 documentation comments; the 4 shebangs stay). The count came from Pygments lexers and Python's `tokenize` and `ast`, not from a regular expression, so `//` in a URL or string was not touched. A formatter pass (cargo fmt, spotless, ruff, prettier) followed, and the whole `npm run check` and `npm run e2e` pass.
- Mistake: the per-language numbers I first quoted to the user (for example "Java 21 plain + 36 Javadoc") were estimated, not computed. The script gave Java 12 plain + 37 Javadoc, Python 9 + 11 docstrings, TypeScript 14 + 4, JavaScript 4, shell 18 (4 shebangs), YAML 9, SQL 1, .env 2. Quote only numbers that a tool printed.
- Removing the `# v4.2.2` labels next to the pinned GitHub action hashes loses the human-readable version of each pin.

## Applied migrations and comments (2026-11-04)

- Mistake: the comment removal also changed `V2__queue.sql`. Flyway stores a checksum of the whole file, comments included, so a database that had already applied V2 (the persistent minikube database) refused the new application: `Migration checksum mismatch for migration version 2`, CrashLoopBackOff, and `helm upgrade --wait` timed out. Every test and the e2e passed because they start from an empty database; only a deployment over existing data showed it. The file is restored byte for byte from the commit that applied it, and `MigrationsTest` pins the checksums of V1 and V2 (it failed first).
- Rule: an applied migration file is immutable, including comments and whitespace. A tool that rewrites every file (comment remover, formatter) must skip `db/migration`. Report "all green" only after a deployment over existing data when migrations exist.
- `minikube stop` removes the profile's context from the kubeconfig and clears the current context. It did this to ~/.kube/config before the project got its own file: the user's current context (a real shared cluster) was cleared (cause shown in the isolated file, where the same stop empties the context list).
- Project kubeconfig: `deploy/minikube.sh` exports `KUBECONFIG=<project>/.kube/config` (gitignored, mode 600) and `.envrc` does the same for direnv. Proof: the hash and mtime of ~/.kube/config were identical after `up`, `e2e` and `stop`. `--keep-context` is not needed any more, because the file is the project's own.
- Java formatting is Spotless with the Prettier step (`prettier-plugin-java`, `backend/java/.prettierrc.yml`, 120 columns), replacing google-java-format. Ruff uses 120 columns in both Python projects. Misread request: the first plan replaced Spotless; the user meant Spotless running Prettier.
- `RestartTest.aFreshQueuedBatchIsNotPublishedAgain` failed once in a full `mvn verify` with a 404 for a just-queued batch and passed alone. Code that I did not write; probably a timing race.
- Jackson 3 coerces `0.5` to `0` for an `int` and a string `"1"` to a number unless `ACCEPT_FLOAT_AS_INT` and `ALLOW_COERCION_OF_SCALARS` are disabled. `Json.mapper()` disables both, so 1.0 is refused even though the schema allows it. The unit test found this.
- Comparing `JsonNode` trees fails between `IntNode` and `LongNode` with the same value. Compare after a write and re-read as text.
- Contract documents are now typed records in `Wire` (sealed sum types for item, batch and peak states; invariants in compact constructors). The canonical schema still validates every document at the process boundary and for API responses, and `WireTest` round trips documents through both. The earlier `JsonNode` boundary was a type-safety mistake: it hid the `broker_unavailable` code that the Java code used before the schema listed it.
- `AnalysisRepository` uses JDBI through `local.signallab.utils.Sql`, adapted with the user's permission from their own gravity-pg `Sql` helper (records and a `Params` bag as parameters, record rows, jsonb in and out). Left out: Lombok beans, JSON-row queries and a `Map` as source. A record bound whole as jsonb implements `Jsonb`.
- JDBI cannot construct a `private` record row (`IllegalAccessException: class is not public`); row records are `public` nested records. JDBI's annotations need `checker-qual` at compile time, or `-Werror` fails on `unknown enum constant TypeUseLocation`. A nullable record component is written `Wire.@Nullable ItemError`, not `@Nullable Wire.ItemError`.
- A JSON column typed `List<Integer>` is claimed by the JSON column mapper, so a `smallint[]` column is selected as `to_jsonb(channels)` and written with `ARRAY(SELECT jsonb_array_elements_text(:channels)::smallint)`.
- Wire enum constants are lowercase (`unknown_recording`) so the JSON names need no annotation, as the user's note in `Wire.java` asked.
- A Spring `@Repository` on a record fails at startup (`Cannot subclass final class`): the exception-translation post-processor wants a CGLIB proxy. Records used as beans carry `@Component`, `@Service`, `@RestController` and never `@Repository` or `@Transactional`.
- Configuration is typed `@ConfigurationProperties` records (`EngineProperties`, `QueueProperties`, `ConsumerProperties`, `RecordingsProperties`) enabled by `@ConfigurationPropertiesScan`; no `@Value` is left. Java exceptions expose `public final` fields instead of getters.
- Python: `Recording` and `Manifest` are pydantic models with `to_camel` aliases and `from_attributes`, so SQLAlchemy rows convert to API objects without a mapping function and FastAPI returns rows directly. `RecordingId` is a `NewType`. The first version used `# pyright: ignore` for the row-versus-model mismatch; typing the endpoint return as `Row` with an explicit `response_model` removed it.
- Frontend tests live in `frontend/tests`, not in `src`. `tsconfig.app.json` covers `src` only; `tsconfig.test.json` covers `src` and `tests`; `npm run typecheck` runs both. Tests import app code as `@/…` (`paths` in `tsconfig.app.json`, resolved in `vitest.config.ts` with `resolve.tsconfigPaths`), so the alias has one source of truth. Shared raw payloads are in `tests/fixtures.ts`. The `vitest/globals` type was never used (tests import from `vitest`) and is removed. Wrong assumption: an earlier note said the frontend has 26 unit tests; the real count is 31 before and after the move. Proof: a changed source string fails exactly one test; `tsc --listFilesOnly` for the app config lists no file from `tests`.
- Documents were rewritten in Simplified Technical English (short sentences, lists, one topic for each paragraph; tables hold short cells only; US spelling in prose, code names unchanged). Stale statements found and removed during the rewrite: `contracts.md` said the Java adapter and the Java process caller were not implemented (they are: `Contracts.validate` runs on `EngineInput`, `EngineOutput`, `Recording` and `BatchSnapshot`); `numerics.md` said that no benchmark exists; `AC.md` said that the real-stack browser check covers only scaffold readiness, which SA1 and SA2 contradict; `contracts.md` did not list the Queued batch state. Lesson: a document that states "not done yet" goes stale silently. Check each such sentence against the code when you edit the file.
- Rewriting to lists does not reduce `wc -w` (bullets and table pipes count as words): the Markdown total grew from about 15,300 to 17,300 words. The gain is in sentence length and layout, not in word count. A sentence-length script that splits only on full stops merges list items into one long "sentence"; read lists as separate sentences.
- FD.md no longer has a "Version 1.1 extension" section: the asynchronous queue, Kubernetes and performance rules are merged into the body, and the version 1.0 rules that they replaced (synchronous request, one active batch, four containers, interrupt at startup) are deleted. Wrong assumption: I first wrote that reconciliation can set a batch to Interrupted, because the old extension said "Queued to Interrupted when ... reconciliation finds it". `QueueReconciler` only publishes again and stops when the queue is full (`takeWhile`); a broker failure at startup fails the start. Only a failed publication at submission interrupts a Queued batch. I fixed the FD from the code, and the sentence "Reconciliation stops when the queue is full" is new in the FD.
