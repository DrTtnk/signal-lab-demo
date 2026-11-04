import { expect, test } from "@playwright/test";
import { ApiClient } from "../api-client";
import {
  analyseSelected,
  analysisResult,
  openCatalogue,
  selectRecording,
  shownBatchId,
} from "../helpers";
import { loadTestData, RECORDING_NAMES } from "../test-data";

test.describe.configure({ mode: "serial" });

test.describe("catalogue and analysis journey", () => {
  let analysedBatchId: string;

  test("1 — the catalogue lists the recordings registered by the recordings service", async ({
    page,
  }) => {
    await openCatalogue(page);

    const table = page.getByRole("table", { name: "Registered recordings" });
    await expect(table.getByRole("row")).toHaveCount(
      RECORDING_NAMES.length + 1,
    );
    for (const name of RECORDING_NAMES) {
      await expect(table.getByRole("rowheader", { name })).toBeVisible();
    }
    const stereo = table.getByRole("row", { name: /stereo-with-tail/ });
    await expect(stereo).toContainText("48 kHz");
    await expect(stereo).toContainText("4099");
  });

  test("2 — analyse one mono recording and read its spectrum", async ({
    page,
  }) => {
    await openCatalogue(page);
    await selectRecording(page, "quarter-cycle-mono");
    await analyseSelected(page);

    const result = analysisResult(page);
    await expect(
      result.getByRole("heading", { name: "Analysis Completed" }),
    ).toBeVisible();
    const channel = result
      .getByRole("region", { name: "quarter-cycle-mono" })
      .getByRole("region", { name: "Channel 0" });
    await expect(channel).toContainText(
      /RMS 0\.1767\d* \(normalised amplitude\)/,
    );
    await expect(channel).toContainText("Peak 12000 Hz (bin 1024)");
    await expect(
      channel.getByRole("img", { name: /Spectrum of channel 0/ }),
    ).toBeVisible();
  });

  test("3 — analyse both channels of a stereo recording with an ignored tail", async ({
    page,
  }) => {
    await openCatalogue(page);
    await selectRecording(page, "stereo-with-tail");
    await analyseSelected(page);

    const recording = analysisResult(page).getByRole("region", {
      name: "stereo-with-tail",
    });
    await expect(recording).toContainText(
      "1 windows, 4096 frames, 3 frames ignored",
    );
    const first = recording.getByRole("region", { name: "Channel 0" });
    await expect(first).toContainText("Peak 12000 Hz (bin 1024)");
    const second = recording.getByRole("region", { name: "Channel 1" });
    await expect(second).toContainText(/RMS 0\.25 \(normalised amplitude\)/);
    await expect(second).toContainText("Peak 0 Hz (bin 0)");
  });

  test("4 — analyse two recordings in one batch; a silent one has no spectral peak", async ({
    page,
  }) => {
    await openCatalogue(page);
    await selectRecording(page, "zero-mono");
    await selectRecording(page, "dc-mono");
    await analyseSelected(page);

    const result = analysisResult(page);
    await expect(
      result.getByRole("heading", { name: "Analysis Completed" }),
    ).toBeVisible();
    await expect(
      result
        .getByRole("region", { name: "zero-mono" })
        .getByText("No spectral peak"),
    ).toBeVisible();
    await expect(
      result
        .getByRole("region", { name: "dc-mono" })
        .getByText("Peak 0 Hz (bin 0)"),
    ).toBeVisible();
    analysedBatchId = await shownBatchId(result);
  });

  test("5 — the batch shown in the browser is persisted and complete", async ({
    request,
  }) => {
    const batch = await new ApiClient(request).batch(analysedBatchId);
    const data = loadTestData();

    expect(batch).toMatchObject({
      batchId: analysedBatchId,
      state: "Completed",
      items: [
        { state: "Succeeded", recordingId: data.recordings["zero-mono"] },
        { state: "Succeeded", recordingId: data.recordings["dc-mono"] },
      ],
    });
  });
});
