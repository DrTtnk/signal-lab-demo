import { describe, expect, it } from "vitest";
import { parseRecordings } from "@/recordings";
import { rawRecording } from "./fixtures";

describe("parseRecordings", () => {
  it("accepts a canonical recording list, including an empty catalogue", () => {
    expect(parseRecordings([rawRecording])).toHaveLength(1);
    expect(parseRecordings([])).toEqual([]);
  });

  it("accepts seeded provenance", () => {
    const seeded = {
      ...rawRecording,
      provenance: { kind: "Seeded", recipeVersion: "v1", seed: 7 },
    };
    expect(parseRecordings([seeded])[0]?.provenance.kind).toBe("Seeded");
  });

  it.each([
    ["an unknown field", { ...rawRecording, extra: 1 }],
    ["a null value", { ...rawRecording, name: null }],
    ["a wrong sample rate", { ...rawRecording, sampleRateHz: 44100 }],
    ["five channels", { ...rawRecording, channelCount: 5 }],
    ["a fractional frame count", { ...rawRecording, frameCount: 4096.5 }],
    ["a malformed digest", { ...rawRecording, sha256: "abc" }],
    ["unknown provenance", { ...rawRecording, provenance: { kind: "Other" } }],
  ])("refuses %s", (_name, recording) => {
    expect(() => parseRecordings([recording])).toThrow();
  });

  it("refuses a payload that is not a list", () => {
    expect(() => parseRecordings(rawRecording)).toThrow();
  });
});
