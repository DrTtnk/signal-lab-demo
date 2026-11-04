# Benchmarks

Status:

- One criterion run of the Rust engine exists (below).
- The k6 full-stack timing run does not exist.

Add a result only after a real run on an idle machine. Include the machine description and the commands.

## Rust engine (criterion)

The benchmark file is `backend/rust/benches/engine.rs`. It has two groups:

- `kernel`: one window of one channel through the FFT and the density accumulation. Throughput is in samples per second.
- `analyse_four_channels`: the full `engine::analyse` call (WAV decoding, SHA-256, FFT) on a recording with four channels and 16, 256 or 1024 windows. Throughput is in bytes per second. The benchmark generates the input in memory from a fixed seed. It reads no file.

The `analyse_four_channels` group runs with 1, 2 and 4 workers. The FFT uses the scalar RustFFT planner. There is no explicit SIMD path.

### Commands

Smoke run. Each benchmark runs once and nothing is timed. `npm run check` runs it.

```sh
cd backend/rust && cargo bench --locked -- --test
```

Timing run. Use an idle machine only. See the performance protocol in the [functional design](FD.md).

```sh
npm run bench --workspace @signal-lab/rust
```

### Result: Rust engine (2026-11-03)

Conditions:

- Intel Core Ultra 9 275HX, 24 cores without SMT.
- The run used CPUs 0-3 (`taskset -c 0-3`). They are four separate cores of the same class (5.3 GHz maximum).
- Governor `performance`, turbo on. The run did not log the frequency.
- rustc and cargo 1.91.0. Release profile of `cargo bench`. No `target-cpu` flag.
- Source commit `0d638f7`.
- Command: `taskset -c 0-3 cargo bench --locked`. One run, criterion defaults (3 s warm-up, 100 samples).

The machine was not fully idle:

- The load average before the run was 1.28.
- A long Python process of the owner used CPU 4 at about 100%.
- CPUs 0-3 were 1-9% busy.
- The minikube profile of this project was stopped. No container ran.

A process that moves onto CPUs 0-3 adds noise that this run does not show. Nothing was repeated, so the variability between runs is unknown. The intervals in this document are the within-run 95% confidence intervals of criterion.

| Benchmark                                      | Time (low / estimate / high) | Throughput (estimate) |
| ---------------------------------------------- | ---------------------------- | --------------------- |
| kernel, one window, one channel (4096 samples) | 20.323 / 20.333 / 20.345 µs  | 201.4 Msamples/s      |
| 16 windows, 4 channels (524,332 B), 1 worker   | 4.452 / 4.539 / 4.682 ms     | 110.2 MiB/s           |
| 16 windows, 2 workers                          | 3.551 / 3.568 / 3.586 ms     | 140.1 MiB/s           |
| 16 windows, 4 workers                          | 2.991 / 3.016 / 3.055 ms     | 165.8 MiB/s           |
| 256 windows (8,388,652 B), 1 worker            | 68.51 / 68.82 / 69.15 ms     | 116.3 MiB/s           |
| 256 windows, 2 workers                         | 56.26 / 56.55 / 56.88 ms     | 141.5 MiB/s           |
| 256 windows, 4 workers                         | 45.25 / 45.52 / 45.83 ms     | 175.7 MiB/s           |
| 1024 windows (33,554,476 B), 1 worker          | 271.0 / 275.0 / 282.1 ms     | 116.4 MiB/s           |
| 1024 windows, 2 workers                        | 222.2 / 222.9 / 223.5 ms     | 143.6 MiB/s           |
| 1024 windows, 4 workers                        | 178.2 / 178.4 / 178.6 ms     | 179.4 MiB/s           |

For the largest input, 2 workers are 1.23 times faster than 1 worker. 4 workers are 1.54 times faster. This is far from linear.

- The kernel shows the cost of the FFT. 4096 window-channel units at 20.3 µs take about 83 ms. This is about 30% of the 275 ms of the single-worker run.
- Decoding, SHA-256 and sample copying take the rest. They run on one thread (see [numerical profile](numerics.md)).
- This agrees with the limit that the sequential part sets. This run did not profile the split. The split is an inference, not a measurement.
- The kernel figure comes from a cache-warm loop on one window. It is not application throughput.

### Result: sequential part of the engine (2026-11-03)

Group `sequential_parts_1024_windows` in `benches/engine.rs`. The input is the same 33,554,476-byte file with four channels. The run uses one thread.

- Command: `taskset -c 5 cargo bench --locked --bench engine -- sequential_parts`.
- Source: commit `fe1de6f` plus the bench group. rustc 1.91.0.
- CPU 5 was 2% busy before the run. The Python process of the owner (100% of one core) was on CPU 3.
- One run. Within-run intervals. The caveats of the previous result apply.

| Part                                                                  | Time (low / estimate / high) | Throughput (estimate) |
| --------------------------------------------------------------------- | ---------------------------- | --------------------- |
| SHA-256 of all bytes, one call                                        | 8.585 / 8.602 / 8.620 ms     | 3.63 GiB/s            |
| Hound decoding of every sample (iterator), no hashing                 | 99.29 / 99.59 / 99.88 ms     | 321 MiB/s             |
| The same, read through the hashing wrapper of the engine (unbuffered) | 122.6 / 122.9 / 123.2 ms     | 260 MiB/s             |
| The same, with a 64 KiB `BufReader` between Hound and the wrapper     | 117.6 / 117.9 / 118.2 ms     | 271 MiB/s             |

Reading, for the single-worker run of 275 ms:

- Hashing is not the problem. It takes 8.6 ms alone. It takes 18-23 ms more when it sits behind the two-byte reads of Hound.
- The Hound decoding of each sample takes about 100 ms. This is the largest sequential part.
- Decoding plus the FFT (about 83 ms) explain about 205 ms of the 275 ms.
- The other 70 ms or so was not measured. It includes sample copying into the window batches, the density sums and the iterator overhead of the engine loop.
- A buffer between Hound and the hash gains only about 5 ms.
- A decoder that reads each window with one `read_exact` and converts the bytes avoids the cost for each sample. The next result shows it.

### Result: bulk decoder (2026-11-03)

Change: Hound parses and checks only the header. The engine reads each window with one `read_exact` and converts the bytes with `i16::from_le_bytes`.

- The 86 Python tests pass before and after the change. Five of them are new: a chunk before the data, and cuts at odd offsets. A byte-order mutation makes the tests fail.
- Source: commit `8010062` plus the change.
- Command: `taskset -c 4-7 target/release/deps/engine-<hash> --bench`. This runs the whole benchmark file once.
- The run used cores 4-7 and not 0-3, because the Python process of the owner had moved to CPU 1. All cores are of the same class.
- The kernel benchmark, which the change does not touch, read 21.3 µs and not 20.3 µs (+5%). Differences of this size between the two runs are noise or core-to-core variation.

| 1024 windows, 4 channels, 33,554,476 B | Before (CPUs 0-3)    | After (CPUs 4-7)     | Speedup |
| -------------------------------------- | -------------------- | -------------------- | ------- |
| 1 worker                               | 275.0 ms (116 MiB/s) | 142.6 ms (224 MiB/s) | 1.93    |
| 2 workers                              | 222.9 ms (144 MiB/s) | 92.8 ms (345 MiB/s)  | 2.40    |
| 4 workers                              | 178.4 ms (179 MiB/s) | 55.0 ms (582 MiB/s)  | 3.24    |

- In the new run, 2 workers are 1.54 times faster than 1 worker. 4 workers are 2.59 times faster. Before the change the values were 1.23 and 1.54.
- The sequential part is now smaller, so the FFT work scales better.
- The 16-window and 256-window inputs show the same result: about 47% less time for 1 worker, and about 69-71% less for 4 workers.
- The estimates are within-run. Two runs on different cores are not a controlled A/B test.

Not done: memory use, repeated runs and the k6 full-stack timing.

## Estimated upper envelope

This is a model. It is not a measurement and not a certified hardware limit.

The script `tools/fixtures/src/signal_lab_fixtures/envelope.py` computes the model. Run it with `uv run python -m signal_lab_fixtures.envelope` in `tools/fixtures`. Its tests check the arithmetic. They also count the operations of a real radix-2 FFT, which matches the NumPy result.

The performance protocol defines the ceiling in samples per second. It is the smaller of two values:

- the execution ceiling: operations available per second, divided by operations per sample
- the transfer ceiling: bytes available per second, divided by bytes moved per sample

| Input                                | Value                     | Source                                                                                                                                                                                                                                                                |
| ------------------------------------ | ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FFT operations per sample, N = 4096  | 45.3 to 60.0              | Low end: 34/9 log2 N (modified split radix, Johnson and Frigo 2007). This is a literature constant. It is not verified here. High end: 5 log2 N (radix 2), counted by the script. The scalar RustFFT planner picks its own algorithm. Its assembly was not inspected. |
| Other operations per sample          | 6.0                       | From `dsp.rs`: Hann multiply, PCM division and multiply, squared magnitude, density scale and weight, accumulation, for each bin and each sample.                                                                                                                     |
| Total operations per sample          | 51.3 to 66.0              | Sum of the two rows above.                                                                                                                                                                                                                                            |
| Clock                                | 5.3 GHz                   | `lscpu` maximum of CPUs 0-7. The runs did not log the clock. The sustained clock can be lower. Assumption.                                                                                                                                                            |
| Scalar operations per cycle and core | 4 (optimistic), 2 (check) | Assumption about the floating-point ports of the core. Not verified. The engine uses scalar FFT code and no explicit SIMD.                                                                                                                                            |
| Cores                                | 1 or 4                    | The runs used one pinned core (kernel) or four (4 workers).                                                                                                                                                                                                           |
| DRAM bytes per sample                | 2                         | The 16-bit sample in the file. At this window size, everything else (window buffers, density sums) stays in cache.                                                                                                                                                    |
| DRAM bandwidth                       | 60 GB/s                   | Assumption for this laptop memory. Not calibrated.                                                                                                                                                                                                                    |

Result at 4 operations per cycle:

- The execution ceiling is 321 to 413 Msamples/s for one core. It is 1285 to 1652 Msamples/s for four cores.
- The transfer ceiling is 30,000 Msamples/s. This is about 20 times higher than the execution ceiling.
- The engine is bound by execution, not by memory transfer.
- At 2 operations per cycle, the one-core ceiling is 161 to 206 Msamples/s.

Proximity to the ceiling. The measured values come from the results above.

| Measurement                | Msamples/s | Share of the ceiling at 4 operations per cycle |
| -------------------------- | ---------- | ---------------------------------------------- |
| Kernel, one core           | 201.4      | 49% to 63%                                     |
| Engine, 4 workers, 4 cores | 305.0      | 18% to 24%                                     |
| Engine, 1 worker           | 117.7      | Not compared (one core, partly sequential)     |

Reading:

- The kernel measurement is 98% to 125% of the ceiling at 2 operations per cycle. This value is possible for the high operation count. It is too low for the low operation count. Under these assumptions, the real figure is between 2 and 4.
- The range of the model is wide, because two inputs are assumptions. The position of the engine relative to the ceiling is known only to about a factor of two.
- The FFT in `dsp.rs` is a complex FFT of real data. A real-input FFT needs about half the operations.
- Scalar code leaves the vector units unused.
- The project implements neither change and claims no speedup for them.

## Full stack (k6)

The script is `tests/load/analysis.js`.

- It reads the catalogue. Each virtual user then submits one analysis of a random recording through the web port.
- It checks that the API accepts the analysis and that the batch state becomes Completed.
- Each iteration expects 202 Queued. It polls the batch every 100 ms until the batch is in a final state.
- The custom metric `batch_latency` is the time from submission to the final state.
- The only threshold is that all checks pass. There is no timing threshold.

The five small fixtures are about 8 KiB each. For this reason the latency comes mostly from the start of the Rust process, HTTP and PostgreSQL. The signal processing is a small part. Large data sets need a separate command and a declared storage budget (see the functional design).

Several virtual users queue batches. When the queue holds the declared maximum (20), the application answers 429 and the checks fail on purpose. Keep the number of virtual users below this limit, or raise `analysis.queue.max-depth`.

To run the test:

1. Install k6.
2. Start the stack (see the README).
3. Run:

```sh
VUS=4 DURATION=30s npm run load
```

A single iteration passes against the real stack as a correctness smoke test. It is not a measurement:

```sh
k6 run --iterations 1 tests/load/analysis.js
```
