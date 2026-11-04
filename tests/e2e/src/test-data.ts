import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { z } from "zod";

export const RECORDING_NAMES = [
  "zero-mono",
  "dc-mono",
  "nyquist-mono",
  "quarter-cycle-mono",
  "stereo-with-tail",
] as const;

const TestData = z.strictObject({
  recordings: z.strictObject(
    Object.fromEntries(
      RECORDING_NAMES.map((name) => [name, z.uuid()]),
    ) as Record<(typeof RECORDING_NAMES)[number], z.ZodUUID>,
  ),
});

export type TestData = z.infer<typeof TestData>;

const PATH = "playwright/.test-data.json";

export function saveTestData(data: TestData): void {
  mkdirSync(dirname(PATH), { recursive: true });
  writeFileSync(PATH, JSON.stringify(TestData.parse(data), null, 2));
}

export function loadTestData(): TestData {
  return TestData.parse(JSON.parse(readFileSync(PATH, "utf8")));
}
