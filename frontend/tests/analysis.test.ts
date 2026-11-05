import { describe, expect, it } from "vitest";
import { buildRequest, isTerminal, parseBatch } from "@/analysis";
import {
  itemId,
  profileId,
  rawBatchBase,
  rawBrokerInterruption,
  rawChannel,
  rawCompletedBatch,
  rawSucceededItem,
  recordingId,
} from "./fixtures";

const engineUnavailable = {
  code: "engine_unavailable",
  message: "Engine cannot start",
};
const notRun = { recordingId, state: "NotRun", reason: "batch_interrupted" };
const pending = { itemId, recordingId, state: "Pending" };

describe("buildRequest", () => {
  it("lists only the selected recordings, each with its sorted unique channels", () => {
    const request = buildRequest(
      [
        { recordingId, channels: [1, 0] },
        { recordingId: "22222222-2222-4222-8222-222222222222", channels: [] },
      ],
      () => itemId,
    );
    expect(request).toEqual({
      contractVersion: "1",
      profileId,
      items: [{ itemId, recordingId, channels: [0, 1] }],
    });
  });

  it("refuses an empty selection", () => {
    expect(() => buildRequest([], () => itemId)).toThrow();
  });
});

describe("parseBatch", () => {
  it("accepts a completed batch", () => {
    expect(parseBatch(rawCompletedBatch).state).toBe("Completed");
  });

  it("accepts failed, interrupted and not-run items", () => {
    const mixed = {
      ...rawCompletedBatch,
      state: "Interrupted",
      error: engineUnavailable,
      items: [
        rawSucceededItem,
        {
          itemId: "44444444-4444-4444-8444-444444444444",
          recordingId,
          state: "Interrupted",
          error: engineUnavailable,
        },
        { itemId: "55555555-5555-4555-8555-555555555555", ...notRun },
      ],
    };
    expect(parseBatch(mixed).items).toHaveLength(3);
  });

  it("accepts a queued batch with pending and failed items", () => {
    const queued = parseBatch({
      ...rawBatchBase,
      state: "Queued",
      items: [
        pending,
        {
          itemId: "66666666-6666-4666-8666-666666666666",
          recordingId,
          state: "Failed",
          error: { code: "unknown_recording", message: "Not registered" },
        },
      ],
    });
    expect(queued.state).toBe("Queued");
  });

  it("accepts an interruption caused by the message broker", () => {
    expect(parseBatch(rawBrokerInterruption).state).toBe("Interrupted");
  });

  it.each([
    [
      "Completed with a pending item",
      { ...rawCompletedBatch, items: [pending] },
    ],
    [
      "AllItemsFailed with a successful item",
      { ...rawCompletedBatch, state: "AllItemsFailed" },
    ],
    [
      "CompletedWithErrors without a failure",
      { ...rawCompletedBatch, state: "CompletedWithErrors" },
    ],
    [
      "Queued with a running item",
      {
        ...rawBatchBase,
        state: "Queued",
        items: [{ ...pending, state: "Running" }],
      },
    ],
    [
      "unknown fixed-pattern provenance",
      {
        ...rawCompletedBatch,
        items: [
          {
            ...rawSucceededItem,
            recordingProvenance: {
              kind: "FixedPattern",
              recipeVersion: "unknown",
              patternName: "unknown",
            },
          },
        ],
      },
    ],
    [
      "a queued batch with a finish time",
      { ...rawCompletedBatch, state: "Queued" },
    ],
    ["an unknown field", { ...rawCompletedBatch, extra: 1 }],
    [
      "a spectrum with the wrong length",
      withChannel({ ...rawChannel, meanPsd: [0] }),
    ],
    ["a negative rms", withChannel({ ...rawChannel, rms: -1 })],
    [
      "an unknown item state",
      {
        ...rawCompletedBatch,
        items: [{ itemId, recordingId, state: "Almost" }],
      },
    ],
  ])("refuses %s", (_name, payload) => {
    expect(() => parseBatch(payload)).toThrow();
  });
});

describe("isTerminal", () => {
  it("is false while a batch is queued or running", () => {
    for (const state of ["Queued", "Running"]) {
      const parsed = parseBatch({ ...rawBatchBase, state, items: [pending] });
      expect(isTerminal(parsed)).toBe(false);
    }
  });

  it("is true for every finished state", () => {
    expect(isTerminal(parseBatch(rawCompletedBatch))).toBe(true);
    expect(isTerminal(parseBatch(rawBrokerInterruption))).toBe(true);
  });
});

function withChannel(channel: unknown) {
  return {
    ...rawCompletedBatch,
    items: [
      {
        ...rawSucceededItem,
        result: { ...rawSucceededItem.result, channels: [channel] },
      },
    ],
  };
}
