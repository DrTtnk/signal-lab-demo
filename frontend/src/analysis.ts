import { z } from "zod"; // prefer effect schema

const PROFILE_ID = "pcm16-48k-hann4096-v1";
const SPECTRUM_BINS = 2049;

const Uuid = z.uuid();
const Timestamp = z.iso.datetime();
const InfrastructureError = z.strictObject({
  code: z.enum([
    "engine_unavailable",
    "recordings_unavailable",
    "database_unavailable",
    "deadline_exceeded",
    "engine_protocol_error",
    "application_stopped",
    "broker_unavailable",
  ]),
  message: z.string().min(1).max(1024),
});
const ItemError = z.strictObject({
  code: z.enum([
    "unknown_recording",
    "channel_unavailable",
    "unsupported_recording",
    "truncated_recording",
    "digest_mismatch",
    "insufficient_frames",
  ]),
  message: z.string().min(1).max(1024),
});

const Peak = z.discriminatedUnion("kind", [
  z.strictObject({ kind: z.literal("NoPeak") }),
  z.strictObject({
    kind: z.literal("Peak"),
    bin: z
      .number()
      .int()
      .min(0)
      .max(SPECTRUM_BINS - 1),
    frequencyHz: z.number().min(0).max(24000),
  }),
]);

const ChannelResult = z.strictObject({
  channelIndex: z.number().int().min(0).max(3),
  rms: z.number().min(0),
  meanPsd: z.array(z.number().min(0)).length(SPECTRUM_BINS),
  bandPowers: z.tuple([
    z.number().min(0),
    z.number().min(0),
    z.number().min(0),
  ]),
  peak: Peak,
});

const AnalysisResult = z.strictObject({
  processedFrames: z.number().int().positive(),
  ignoredTailFrames: z.number().int().min(0).max(4095),
  processedWindows: z.number().int().positive(),
  channels: z.array(ChannelResult).min(1).max(4),
});

const Provenance = z.discriminatedUnion("kind", [
  z.strictObject({
    kind: z.literal("FixedPattern"),
    recipeVersion: z.string().min(1),
    patternName: z.string().min(1),
  }),
  z.strictObject({
    kind: z.literal("Seeded"),
    recipeVersion: z.string().min(1),
    seed: z.number().int().nonnegative(),
  }),
]);

const itemBase = { itemId: Uuid, recordingId: Uuid };
const Item = z.discriminatedUnion("state", [
  z.strictObject({ ...itemBase, state: z.literal("Pending") }),
  z.strictObject({ ...itemBase, state: z.literal("Running") }),
  z.strictObject({
    ...itemBase,
    state: z.literal("Succeeded"),
    recordingSha256: z.string().regex(/^[0-9a-f]{64}$/),
    profileId: z.literal(PROFILE_ID),
    engineBuild: z.string().min(1).max(256),
    workers: z.union([z.literal(1), z.literal(2), z.literal(4)]),
    result: AnalysisResult,
    recordingProvenance: Provenance,
  }),
  z.strictObject({ ...itemBase, state: z.literal("Failed"), error: ItemError }),
  z.strictObject({
    ...itemBase,
    state: z.literal("Interrupted"),
    error: InfrastructureError,
  }),
  z.strictObject({
    ...itemBase,
    state: z.literal("NotRun"),
    reason: z.literal("batch_interrupted"),
  }),
]);

const batchBase = {
  contractVersion: z.literal("1"),
  batchId: Uuid,
  profileId: z.literal(PROFILE_ID),
  createdAt: Timestamp,
  items: z.array(Item).min(1),
};
const Batch = z.discriminatedUnion("state", [
  z.strictObject({ ...batchBase, state: z.literal("Queued") }),
  z.strictObject({ ...batchBase, state: z.literal("Running") }),
  ...(["Completed", "CompletedWithErrors", "AllItemsFailed"] as const).map(
    (state) =>
      z.strictObject({
        ...batchBase,
        state: z.literal(state),
        finishedAt: Timestamp,
      }),
  ),
  z.strictObject({
    ...batchBase,
    state: z.literal("Interrupted"),
    finishedAt: Timestamp,
    error: InfrastructureError,
  }),
]);

export type Batch = z.infer<typeof Batch>;
export type BatchItem = Batch["items"][number];
export type SucceededItem = Extract<BatchItem, { state: "Succeeded" }>;

export function parseBatch(payload: unknown): Batch {
  return Batch.parse(payload);
}

export function isTerminal(batch: Batch): boolean {
  return batch.state !== "Queued" && batch.state !== "Running";
}

export const POLL_INTERVAL_MS = 2000;

export async function fetchBatch(batchId: string): Promise<Batch> {
  const response = await fetch(`/api/analyses/${batchId}`);
  if (!response.ok) {
    throw new Error(
      `Batch ${batchId} returned HTTP ${String(response.status)}`,
    );
  }
  return parseBatch(await response.json());
}

export interface Selection {
  recordingId: string;
  channels: readonly number[];
}

export interface AnalysisRequest {
  contractVersion: "1";
  profileId: typeof PROFILE_ID;
  items: { itemId: string; recordingId: string; channels: number[] }[];
}

export function buildRequest(
  selections: readonly Selection[],
  newItemId: () => string,
): AnalysisRequest {
  const items = selections
    .filter((selection) => selection.channels.length > 0)
    .map((selection) => ({
      itemId: newItemId(),
      recordingId: selection.recordingId,
      channels: [...new Set(selection.channels)].sort((a, b) => a - b),
    }));
  if (items.length === 0) {
    throw new Error("Select at least one recording and channel");
  }
  return { contractVersion: "1", profileId: PROFILE_ID, items };
}

const RejectedRequest = z.strictObject({
  code: z.enum(["invalid_request", "capacity_busy"]),
  message: z.string(),
});

export type AnalysisOutcome =
  | { status: "batch"; batch: Batch }
  | { status: "failed"; code: string; message: string };

export async function submitAnalysis(
  request: AnalysisRequest,
): Promise<AnalysisOutcome> {
  const response = await fetch("/api/analyses", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  const payload: unknown = await response.json();
  if (response.ok || response.status === 503) {
    const batch = Batch.safeParse(payload);
    if (batch.success) {
      return { status: "batch", batch: batch.data };
    }
    if (response.ok) {
      throw batch.error;
    }
    return { status: "failed", ...InfrastructureError.parse(payload) };
  }
  return { status: "failed", ...RejectedRequest.parse(payload) };
}
