import { expect, test } from "@playwright/test";
import { ApiClient } from "../../api-client";
import {
  analyseSelected,
  analysisResult,
  openCatalogue,
  selectRecording,
  shownBatchId,
} from "../../helpers";
import { stack } from "../../stack";

test.describe.configure({ mode: "serial", timeout: 60_000 });

test.describe("application restart", () => {
  let batchId: string;

  test("1 — analyse a recording in the browser", async ({ page }) => {
    await openCatalogue(page);
    await selectRecording(page, "quarter-cycle-mono");
    await analyseSelected(page);

    const result = analysisResult(page);
    await expect(
      result.getByRole("heading", { name: "Analysis Completed" }),
    ).toBeVisible();
    batchId = await shownBatchId(result);
  });

  test("2 — restart the application and wait until the stack is healthy", async () => {
    stack.restart("application");
    await stack.restore();

    expect(stack.running("application")).toBe(true);
  });

  test("3 — the completed batch survived the restart", async ({ request }) => {
    const batch = await new ApiClient(request).batch(batchId);

    expect(batch).toMatchObject({
      batchId,
      state: "Completed",
      items: [{ state: "Succeeded" }],
    });
  });

  test("4 — a new analysis completes in the browser after the restart", async ({
    page,
  }) => {
    await openCatalogue(page);
    await selectRecording(page, "dc-mono");
    await analyseSelected(page);

    await expect(
      analysisResult(page).getByRole("heading", { name: "Analysis Completed" }),
    ).toBeVisible();
  });
});
