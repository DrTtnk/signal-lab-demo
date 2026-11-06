import { z } from "zod";

const Provenance = z.discriminatedUnion("kind", [
  z.strictObject({
    kind: z.literal("FixedPattern"),
    recipeVersion: z.string().min(1),
    patternName: z.string().min(1),
  }),
  z.strictObject({
    kind: z.literal("Seeded"),
    recipeVersion: z.string().min(1),
    seed: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
  }),
]);

const Recording = z.strictObject({
  recordingId: z.uuid(),
  name: z.string().min(1).max(80),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
  byteLength: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
  frameCount: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
  channelCount: z.number().int().min(1).max(4),
  sampleRateHz: z.literal(48000),
  encoding: z.literal("wav_pcm_s16le"),
  provenance: Provenance,
});

const InfrastructureError = z.strictObject({
  code: z.enum([
    "engine_unavailable",
    "recordings_unavailable",
    "database_unavailable",
    "deadline_exceeded",
    "engine_protocol_error",
    "application_stopped",
  ]),
  message: z.string().min(1).max(1024),
});

export type Recording = z.infer<typeof Recording>;

export function parseRecordings(payload: unknown): Recording[] {
  return z.array(Recording).parse(payload);
}

export type RecordingsOutcome =
  | { status: "ready"; recordings: Recording[] }
  | { status: "failed"; code: string; message: string };

export async function fetchRecordings(
  apiOrigin = "",
): Promise<RecordingsOutcome> {
  const response = await fetch(`${apiOrigin}/api/recordings`);
  const payload: unknown = await response.json();
  if (response.ok) {
    return { status: "ready", recordings: parseRecordings(payload) };
  }
  return { status: "failed", ...InfrastructureError.parse(payload) };
}
