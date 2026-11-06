import { expect, test } from "@playwright/test";
import { ApiClient } from "../api-client";
import { openClient } from "../helpers";
import { loadTestData } from "../test-data";

for (const path of ["/angular/", "/native/"] as const) {
  test(`${path} completes a multi-recording analysis through the real API`, async ({
    page,
    request,
  }) => {
    await openClient(page, path);
    const analyse = page.getByRole("button", { name: "Analyse selected" });
    await expect(analyse).toBeDisabled();

    await page
      .getByRole("checkbox", { name: "Analyse stereo-with-tail" })
      .click();
    const secondChannel = page.getByRole("checkbox", {
      name: "Channel 1 of stereo-with-tail",
    });
    await expect(secondChannel).toBeChecked();
    await secondChannel.click();
    await expect(secondChannel).not.toBeChecked();
    await page.getByRole("checkbox", { name: "Analyse zero-mono" }).click();
    await analyse.click();

    await expect(
      page.getByRole("heading", { name: "Analysis Completed" }),
    ).toBeVisible();
    await expect(
      page.getByText("1 windows, 4096 frames, 3 frames ignored"),
    ).toBeVisible();
    await expect(page.getByText("Peak 12000 Hz (bin 1024)")).toBeVisible();
    await expect(page.getByText("No spectral peak")).toBeVisible();
    await expect(page.getByText(/Band 3/)).toHaveCount(2);
    await expect(
      page.getByRole("img", { name: /Spectrum of channel 0/ }),
    ).toHaveCount(2);
    await expect(
      page.getByRole("img", { name: /Spectrum of channel 1/ }),
    ).toHaveCount(0);

    const batchLabel = await page.getByText(/^Batch /).innerText();
    const batch = await new ApiClient(request).batch(
      batchLabel.slice("Batch ".length),
    );
    const { recordings } = loadTestData();
    expect(batch).toMatchObject({
      state: "Completed",
      items: [
        {
          state: "Succeeded",
          recordingId: recordings["stereo-with-tail"],
          result: { channels: [{ channelIndex: 0 }] },
        },
        { state: "Succeeded", recordingId: recordings["zero-mono"] },
      ],
    });
  });
}
