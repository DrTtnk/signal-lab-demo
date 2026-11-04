import { expect, test } from "@playwright/test";
import {
  analyseSelected,
  analysisResult,
  openCatalogue,
  selectRecording,
} from "../../helpers";
import { stack } from "../../stack";

test.describe.configure({ mode: "serial", timeout: 60_000 });

test.describe("message broker outage", () => {
  test.afterAll(async () => {
    await stack.restore();
  });

  test("1 — stop the message broker", () => {
    stack.stop("rabbitmq");

    expect(stack.running("rabbitmq")).toBe(false);
  });

  test("2 — an analysis is refused as interrupted, never shown as a success", async ({
    page,
  }) => {
    await openCatalogue(page);
    await selectRecording(page, "dc-mono");
    await analyseSelected(page);

    const result = analysisResult(page);
    await expect(
      result.getByRole("heading", { name: "Analysis Interrupted" }),
    ).toBeVisible();
    await expect(result.getByRole("alert")).toContainText("broker_unavailable");
    await expect(result.getByText("Not run: batch interrupted")).toBeVisible();
  });

  test("3 — start the message broker again and wait until the stack is healthy", async () => {
    await stack.restore();

    expect(stack.running("rabbitmq")).toBe(true);
  });

  test("4 — the same analysis completes after the recovery", async ({
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
