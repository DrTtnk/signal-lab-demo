import { z } from "zod";
import Ajv2020 from "ajv/dist/2020";
import addFormats from "ajv-formats";
import contract from "../../contracts/v1/schema.json";

const PROFILE_ID = "pcm16-48k-hann4096-v1";
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
type InfrastructureError = z.infer<typeof InfrastructureError>;

interface ItemIdentity {
  itemId: string;
  recordingId: string;
}
interface ItemError {
  code:
    | "unknown_recording"
    | "channel_unavailable"
    | "unsupported_recording"
    | "truncated_recording"
    | "digest_mismatch"
    | "insufficient_frames";
  message: string;
}

export type SucceededItem = ItemIdentity & {
  state: "Succeeded";
  recordingSha256: string;
  profileId: typeof PROFILE_ID;
  engineBuild: string;
  workers: 1 | 2 | 4;
  recordingProvenance:
    | {
        kind: "FixedPattern";
        recipeVersion: "integer-patterns-v1";
        patternName:
          | "zero-mono"
          | "dc-mono"
          | "nyquist-mono"
          | "quarter-cycle-mono"
          | "stereo-with-tail";
      }
    | { kind: "Seeded"; recipeVersion: string; seed: number };
  result: {
    processedFrames: number;
    ignoredTailFrames: number;
    processedWindows: number;
    channels: {
      channelIndex: number;
      rms: number;
      meanPsd: number[];
      bandPowers: [number, number, number];
      peak:
        { kind: "NoPeak" } | { kind: "Peak"; bin: number; frequencyHz: number };
    }[];
  };
};

export type BatchItem =
  | SucceededItem
  | (ItemIdentity &
      (
        | { state: "Pending" | "Running" }
        | { state: "Failed"; error: ItemError }
        | { state: "Interrupted"; error: InfrastructureError }
        | { state: "NotRun"; reason: "batch_interrupted" }
      ));

interface BatchBase {
  contractVersion: "1";
  batchId: string;
  profileId: typeof PROFILE_ID;
  createdAt: string;
  items: BatchItem[];
}
export type Batch = BatchBase &
  (
    | { state: "Queued" | "Running" }
    | {
        state: "Completed" | "CompletedWithErrors" | "AllItemsFailed";
        finishedAt: string;
      }
    | { state: "Interrupted"; finishedAt: string; error: InfrastructureError }
  );

// The shared schema owns runtime validation, including batch/item state rules.
// Its allOf fragments inherit their types from their parent objects.
const ajv = new Ajv2020({ strict: true, strictTypes: false });
addFormats(ajv);
ajv.addSchema(contract);
const validateBatch = ajv.compile<Batch>({
  $ref: contract.$id + "#/$defs/BatchSnapshot",
});

export function parseBatch(payload: unknown): Batch {
  if (!validateBatch(payload)) {
    throw new Error(ajv.errorsText(validateBatch.errors));
  }
  return payload;
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
    if (response.ok || validateBatch(payload)) {
      return { status: "batch", batch: parseBatch(payload) };
    }
    return { status: "failed", ...InfrastructureError.parse(payload) };
  }
  return { status: "failed", ...RejectedRequest.parse(payload) };
}
