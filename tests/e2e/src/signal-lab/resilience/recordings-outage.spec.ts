import { expect, test } from "@playwright/test";
import { analyseSelected, openCatalogue, selectRecording } from "../../helpers";
import { stack } from "../../stack";

test.describe.configure({ mode: "serial", timeout: 60_000 });

test.describe("recordings service outage", () => {
  test.afterAll(async () => {
    await stack.restore();
  });

  test("1 — stop the recordings service after the catalogue is on screen", async ({
    page,
  }) => {
    await openCatalogue(page);
    await selectRecording(page, "nyquist-mono");

    stack.stop("recordings");

    expect(stack.running("recordings")).toBe(false);
    await analyseSelected(page);
    await expect(page.getByRole("alert")).toContainText(
      "recordings_unavailable",
    );
  });

  test("2 — start the recordings service again and wait until the stack is healthy", async () => {
    await stack.restore();

    expect(stack.running("recordings")).toBe(true);
  });

  test("3 — the analysis completes after the recovery", async ({ page }) => {
    await openCatalogue(page);
    await selectRecording(page, "nyquist-mono");
    await analyseSelected(page);

    await expect(
      page.getByRole("heading", { name: "Analysis Completed" }),
    ).toBeVisible();
  });
});
