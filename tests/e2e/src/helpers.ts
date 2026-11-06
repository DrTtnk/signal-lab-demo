import { expect, type Locator, type Page } from "@playwright/test";

export async function openClient(
  page: Page,
  path: "/" | "/angular/" | "/native/",
): Promise<void> {
  await page.goto(path);
}

export async function openCatalogue(page: Page): Promise<void> {
  await openClient(page, "/");
  await expect(
    page.getByRole("table", { name: "Registered recordings" }),
  ).toBeVisible();
}

export async function selectRecording(page: Page, name: string): Promise<void> {
  await page.getByRole("checkbox", { name: `Analyse ${name}` }).check();
}

export async function analyseSelected(page: Page): Promise<void> {
  await page.getByRole("button", { name: "Analyse selected" }).click();
}

export function analysisResult(page: Page): Locator {
  return page.getByRole("region", { name: "Analysis result" });
}

export async function shownBatchId(result: Locator): Promise<string> {
  const text = await result.getByText(/^Batch /).textContent();
  const match = /^Batch ([0-9a-f-]{36})$/.exec(text ?? "");
  if (match?.[1] === undefined) {
    throw new Error(`No batch id in "${String(text)}"`);
  }
  return match[1];
}
