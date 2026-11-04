# Shared contracts

These files are the authority for version 1:

- [schema.json](../contracts/v1/schema.json): the canonical wire definitions, in JSON Schema Draft 2020-12.
- [profile.json](../contracts/v1/profile.json): the fixed numerical values.
- [tolerances.json](../contracts/v1/tolerances.json): the limits for the comparison of metrics.

Do not keep separate copies of their values in four languages.

## Payloads and states

| Definition                      | Purpose                                                                                                                                                             |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| RecordingManifest and Recording | Identity of a managed recording: name, digest, format, frame count and reproducible provenance. The catalogue can be empty.                                         |
| AnalysisRequest                 | A batch that is not empty. It has unique item identifiers, the selected recordings and unique channel selections.                                                   |
| EngineInput                     | One item, its immutable recording snapshot, the fixed profile and a worker count of 1, 2 or 4.                                                                      |
| EngineOutput                    | A SucceededItem or a FailedItem. It has no nullable success or error payload.                                                                                       |
| AnalysisResult                  | Counts of processed frames and windows, the ignored tail, and compact numerical results for each channel.                                                           |
| BatchSnapshot                   | Queued, Running, Completed, CompletedWithErrors, AllItemsFailed or Interrupted. Each state has its permitted item states.                                           |
| RecordingProvenance             | FixedPattern with the identity of its recipe and pattern, or Seeded with the identity of its recipe and an explicit seed. A fixed recipe has no dummy seed or null. |

## Validation rules

All defined object properties are closed. The schema rejects:

- unknown fields
- unsupported versions
- missing required fields
- null values

The schema checks the formats of UUIDs, digests and timestamps.

- Use real finite JSON numbers. NaN, infinity and numbers that overflow to infinity in the decoder are not wire values.
- Integer fields follow the JSON Schema meaning of integer. They stay in the exact integer range of JavaScript.
- Strings, booleans and fractional values are not integers.

A malformed envelope is rejected before a batch exists.

- A well-formed request that names an unknown recording is structurally valid. The application must return an explicit failure for this item. The other valid items stay eligible.
- An empty catalogue is a valid state. It is not a fallback answer for a database or asset service that failed.

Batch states and the finish time:

- A Running snapshot has no `finishedAt`. A terminal snapshot requires it.
- Completed contains only successful items.
- CompletedWithErrors contains successes and item failures.
- AllItemsFailed contains only item failures.
- Interrupted keeps the earlier results and marks the unfinished items.
- No state uses a nullable finish time or a nullable result.

## Semantic checks

JSON Schema cannot compare arbitrary sibling values. The conformance checks must also enforce these rules:

- unique request identities and batch identities
- at most one Running item
- coherent counts of frames and windows
- unique result channels
- peak selection in the emitted spectrum
- band integration

The checks of the engine response bind the output to its input. They check the item and recording identity, the digest, the provenance, the profile, the worker setting, the frame coverage and the order of the selected channels. A result that is valid in syntax but belongs to a different recording is not accepted.

The peak:

- The peak is the first maximum of the emitted spectrum.
- A spectrum with only zero values has NoPeak.
- The RMS can stay nonzero in this case, because the Hann window suppresses the first sample.
- The frequency comes from the selected bin. The system stores no separate rounded frequency as a second authority.

## Where the rules run

- The Rust scalar adapter validates EngineInput against the embedded canonical schema before the typed deserialization.
- The Python checker in `tools/fixtures` validates the outputs of its processes against the schema and these relations.
- The Java application validates the engine input, the engine output, the recordings from the catalogue and the batch snapshots against the canonical schema at runtime. Typed records in `Wire` round-trip with the schema (`WireTest`).
- The TypeScript frontend parses payloads with zod schemas that people write by hand. No check compares them with the canonical schema.
- The schema-level contract tests (valid and refused payloads) are removed. A rewrite is pending.
- A payload schema cannot prove temporal transitions and resource ownership. These need witnesses in the application and the database.

## Java and Rust process protocol

The operation `signal-lab-engine analyse` handles one request for each process.

**Input** on standard input:

1. One UTF-8 EngineInput JSON line, which ends with LF. JSON strings must escape line breaks. The control line has a limit of 64 KiB, including the LF.
2. The exact WAV bytes.
3. EOF.

The byte count and the SHA-256 of the binary part must match the recording snapshot. Raw samples are never JSON arrays.

**Output** on standard output: exactly one UTF-8 EngineOutput JSON line for a completed item. Diagnostics use standard error. They never enter the JSON stream.

**Exit codes:**

| Code | Meaning                                   |
| ---- | ----------------------------------------- |
| 0    | Success.                                  |
| 2    | A typed refusal of the recording.         |
| 64   | Malformed control input.                  |
| 70   | An unexpected engine or protocol failure. |

- A non-success exit cannot become a success.
- Missing, malformed, extra or contradictory output is an infrastructure failure.

**Workers:**

- The engine accepts the workers 1, 2 and 4, which are the values of the shared contract. Any other value fails the schema and gets exit 64.
- The engine analyzes the windows of one item in parallel on a thread pool of exactly that size. It adds the results of the windows in window order. For this reason, every worker count gives the same output bits.
- Help and version stay available.

**WAV input:**

- The input is standard RIFF/WAVE PCM16. RF64 is not supported. Each file must fit the 32-bit length of RIFF.
- The RIFF size must match `byteLength`. The frame count, the channel count and the format must match the snapshot.
- All bytes take part in the SHA-256, including trailing RIFF metadata.
- The reader checks at most `byteLength + 1` bytes. Extra bytes cannot cause an unbounded drain.
- A success needs EOF at the exact byte count and a matching digest. A refused item can stop reading at once.

**Parsing of the control line:**

- The engine validates the line before it converts it to Rust types.
- Integral number forms such as `1.0` are converted only after the canonical schema verified their integer value and their exact-range bound.
- The engine never converts strings, booleans or fractional values.
- The schema validator does not fetch remote or file references.
- The process tests use real stdin and stdout and the real executable. They do not use a substitute engine.

**Build label:** the label is `0.1.0+scalar-fft-parallel-windows-v1`. It identifies this phase of the implementation. It is not a source commit hash and not a complete replay identity.

## Versioning and verification

Version 1 has one fixed profile: `pcm16-48k-hann4096-v1`.

- A new numerical interpretation, normalization, field meaning or incompatible state needs a contract or profile version that people review.
- New definitions do not change the meaning of old payloads.
- Do not widen the tolerances silently to make a failing implementation pass.
