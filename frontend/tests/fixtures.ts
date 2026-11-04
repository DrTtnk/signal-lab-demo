export const recordingId = "8069a3fc-629d-5e48-93ac-52639e88c09d";
export const itemId = "11111111-1111-4111-8111-111111111111";
export const profileId = "pcm16-48k-hann4096-v1";
export const finishedAt = "2026-11-03T06:40:01.123456Z";

export const spectrumBins = 2049;

export function silence() {
  return Array.from({ length: spectrumBins }, () => 0);
}

export const rawRecording = {
  recordingId,
  name: "dc-mono",
  sha256: "aaf8407b51598e1937532a8d9b82676e1e5ad03cdc16e543e35fe40357019646",
  byteLength: 8236,
  frameCount: 4096,
  channelCount: 1,
  sampleRateHz: 48000,
  encoding: "wav_pcm_s16le",
  provenance: {
    kind: "FixedPattern",
    recipeVersion: "integer-patterns-v1",
    patternName: "dc-mono",
  },
};

export const rawChannel = {
  channelIndex: 0,
  rms: 0.25,
  meanPsd: silence(),
  bandPowers: [0.1, 0, 0],
  peak: { kind: "Peak", bin: 0, frequencyHz: 0 },
};

export const rawSucceededItem = {
  itemId,
  recordingId,
  state: "Succeeded",
  recordingSha256: "a".repeat(64),
  profileId,
  engineBuild: "0.1.0+scalar-v1",
  workers: 1,
  recordingProvenance: rawRecording.provenance,
  result: {
    processedFrames: 4096,
    ignoredTailFrames: 0,
    processedWindows: 1,
    channels: [rawChannel],
  },
};

export const rawBatchBase = {
  contractVersion: "1",
  batchId: "33333333-3333-4333-8333-333333333333",
  profileId,
  createdAt: "2026-11-03T06:40:00.123456Z",
};

export const rawCompletedBatch = {
  ...rawBatchBase,
  state: "Completed",
  finishedAt,
  items: [rawSucceededItem],
};

export const rawBrokerInterruption = {
  ...rawBatchBase,
  state: "Interrupted",
  finishedAt,
  error: { code: "broker_unavailable", message: "The broker failed" },
  items: [
    { itemId, recordingId, state: "NotRun", reason: "batch_interrupted" },
  ],
};
