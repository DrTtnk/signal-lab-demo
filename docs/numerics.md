# Numerical profile

[profile.json](../contracts/v1/profile.json) has the authoritative parameters:

- PCM16 at 48 kHz
- non-overlapping windows of 4096 frames
- periodic Hann window, no detrending
- f64 accumulation
- a one-sided density spectrum, averaged arithmetically over complete windows

The bin spacing is 11.71875 Hz. The final bin is Nyquist at 24 kHz.

## Definitions

For a decoded signed sample s, the normalized sample is x = s / 32768. These symbols are used below:

- N is the window length.
- M is the number of complete windows.
- fs is the sample rate.
- n is the sample index in a window.

The definitions:

- Periodic Hann: w[n] = (1 - cos(2*pi*n/N)) / 2.
- Window energy: U = sum(w[n]^2).
- X_j[k] is the unnormalized forward DFT of x_j[n]*w[n].
- The one-sided weight a[k] is 1 at DC and Nyquist. It is 2 at interior bins.
- Density of one window: D_j[k] = a[k] * abs(X_j[k])^2 / (fs*U).
- Mean density: D[k] = sum(D_j[k]) / M.
- Band power: sum(D[k] over the bins of the band) * fs/N.
- Unwindowed RMS: sqrt(sum(x_j[n]^2 over all processed windows) / (M*N)).

The bands are half-open bin intervals: [0,128), [128,512) and [512,2049). The final interval includes Nyquist. The union of the bands covers each one-sided bin once.

The engine ignores the trailing frames that do not fill a window. It excludes them from the RMS and the spectrum, and it reports them.

Density has the unit "normalized amplitude squared per Hz". Band power has the unit "normalized amplitude squared". Neither value is physical energy in joules or calibrated sensor power.

## What is not equal

The integral of the one-sided density gives the window-weighted mean square, averaged over the windows:

`sum(D[k]) * fs/N = mean_j(sum(x_j[n]^2 * w[n]^2) / U)`

This value is not, in general, the square of the unwindowed RMS. An impulse at the first sample shows this. Its RMS is not zero, but the periodic Hann window gives it zero spectral power.

## Tolerances

[tolerances.json](../contracts/v1/tolerances.json) is the single comparison policy.

- A frequency derived from a bin is exact for this profile.
- The PSD tolerance does not change the peak-selection rule. When two emitted maxima are equal, the lowest bin wins.
- The tolerances are conformance limits for this profile. They are not an error bound for any input or any FFT implementation.

## Independent reference

The reference is in `tools/fixtures`. It calls SciPy Welch with every option set explicitly. Its tests run the real Rust executable on these inputs, with the canonical tolerances:

- the five small fixtures
- seeded cases with four channels
- numerical edge cases
- typed refusals

The reference accepts files up to 1 MiB. It also checks that two and four workers give exactly the same result as one worker. It does not check full application results.

The SciPy comparison covers the same normalization as the earlier exact SymPy identities. The project dropped the SymPy identities.

## Scalar Rust core

- Hound decodes PCM as a stream. RustFFT `FftPlannerScalar` computes the forward FFT.
- The core builds one plan and one periodic Hann window for each process.
- It reuses the FFT input and scratch buffers across windows and selected channels.
- Signal storage is O(windowFrames × selectedChannels). It does not depend on the recording length, because the core never reads the full recording into memory.
- The control line of the process has a separate limit of 64 KiB.

RMS uses an exact u64 sum of the squares of the raw PCM16 samples. It converts the sum once, at the end.

- A runnable Rust check covers the bound: u32::MAX frames times the largest sample square (2^30). The product fits in u64.
- The RIFF decoder cannot supply more frames for each channel.
- PSD accumulation and the final normalization use f64.
- The engine selects the peak from the emitted spectrum. It uses a strict greater-than comparison to keep the first exact tie.

## Parallel windows

The engine does these steps:

1. Decode the recording in sequence.
2. Fill a batch of at most 4 × workers windows for each selected channel.
3. Analyze the batch on a rayon pool of exactly `workers` threads (1, 2 or 4).
4. Add the density terms and the integer square sums to the channel sums in window order.

Each term uses the same expression as the single-worker path. The addition order does not depend on the worker count. For this reason, the output is bit-identical for every worker count, and the comparison does not need the tolerances.

Signal and density storage is O(4 × workers × windowFrames × selectedChannels). It does not depend on the recording length.

Decoding stays on one thread. Hound checks the header. The engine then reads each window with one `read_exact` and converts the bytes. Hound's iterator for each sample costs about as much as the FFT.

The speedup has an upper limit: the share of time outside the FFT. [Benchmarks](benchmarks.md) gives the measured values.

There is no explicit SIMD path. Library internals and compiler optimization do not prove a speedup. These values are not measured: CPU affinity, four-core scaling, throughput, bandwidth and the distance from a theoretical bound.
