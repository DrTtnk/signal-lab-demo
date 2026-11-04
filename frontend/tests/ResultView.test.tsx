import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { parseBatch } from "@/analysis";
import { ResultView } from "@/ResultView";
import {
  finishedAt,
  itemId,
  rawBatchBase,
  rawChannel,
  rawCompletedBatch,
  rawSucceededItem,
  recordingId,
  silence,
  spectrumBins,
} from "./fixtures";

const names = new Map([[recordingId, "tone"]]);

function completedWith(result: object) {
  return parseBatch({
    ...rawCompletedBatch,
    items: [
      {
        ...rawSucceededItem,
        recordingProvenance: { kind: "Seeded", recipeVersion: "v1", seed: 3 },
        result: { ...rawSucceededItem.result, ...result },
      },
    ],
  });
}

describe("ResultView", () => {
  const completed = completedWith({
    processedFrames: 8192,
    ignoredTailFrames: 3,
    processedWindows: 2,
    channels: [
      {
        ...rawChannel,
        channelIndex: 1,
        meanPsd: Array.from({ length: spectrumBins }, (_, bin) =>
          bin === 1024 ? 1 : 0,
        ),
        bandPowers: [0, 0.0625, 0],
        peak: { kind: "Peak", bin: 1024, frequencyHz: 12000 },
      },
    ],
  });

  it("shows the numerical result with units", () => {
    render(<ResultView batch={completed} names={names} />);
    const result = screen.getByRole("region", { name: "tone" });
    expect(within(result).getByText("Channel 1")).toBeTruthy();
    expect(
      within(result).getByText("RMS 0.25 (normalised amplitude)"),
    ).toBeTruthy();
    expect(within(result).getByText("Peak 12000 Hz (bin 1024)")).toBeTruthy();
    expect(
      within(result).getByText("2 windows, 8192 frames, 3 frames ignored"),
    ).toBeTruthy();
    expect(
      within(result).getByText(
        "Band 2 (1500–6000 Hz): 0.0625 normalised amplitude²",
      ),
    ).toBeTruthy();
  });

  it("draws one point per spectrum bin with a text alternative", () => {
    render(<ResultView batch={completed} names={names} />);
    const image = screen.getByRole("img", { name: /Spectrum of channel 1/ });
    const line = image.querySelector("polyline");
    expect(line?.getAttribute("points")?.split(" ")).toHaveLength(spectrumBins);
  });

  it("states a missing peak instead of showing 0 Hz", () => {
    const silent = completedWith({
      channels: [
        {
          ...rawChannel,
          rms: 0,
          meanPsd: silence(),
          bandPowers: [0, 0, 0],
          peak: { kind: "NoPeak" },
        },
      ],
    });
    render(<ResultView batch={silent} names={names} />);
    expect(screen.getByText("No spectral peak")).toBeTruthy();
  });

  it("shows item failures and an interrupted batch as alerts", () => {
    const failed = parseBatch({
      ...rawBatchBase,
      state: "Interrupted",
      finishedAt,
      error: { code: "engine_unavailable", message: "Engine cannot start" },
      items: [
        {
          itemId,
          recordingId,
          state: "Failed",
          error: { code: "digest_mismatch", message: "Digest differs" },
        },
        {
          itemId: "22222222-2222-4222-8222-222222222222",
          recordingId,
          state: "NotRun",
          reason: "batch_interrupted",
        },
      ],
    });
    render(<ResultView batch={failed} names={names} />);
    const alerts = screen
      .getAllByRole("alert")
      .map((alert) => alert.textContent);
    expect(alerts.join("|")).toContain(
      "engine_unavailable: Engine cannot start",
    );
    expect(alerts.join("|")).toContain("digest_mismatch: Digest differs");
    expect(screen.getByText("Not run: batch interrupted")).toBeTruthy();
  });
});
