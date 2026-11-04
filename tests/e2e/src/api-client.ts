import { expect, type APIRequestContext } from "@playwright/test";
import { z } from "zod";

const Recordings = z.array(
  z.looseObject({
    recordingId: z.uuid(),
    name: z.string(),
    channelCount: z.number(),
  }),
);

const Batch = z.looseObject({
  batchId: z.uuid(),
  state: z.string(),
  items: z.array(z.looseObject({ state: z.string() })),
});

export type Batch = z.infer<typeof Batch>;

export class ApiClient {
  constructor(private readonly request: APIRequestContext) {}

  async recordings(): Promise<z.infer<typeof Recordings>> {
    const response = await this.request.get("/api/recordings");
    expect(response.status()).toBe(200);
    const body: unknown = await response.json();
    return Recordings.parse(body);
  }

  async batch(batchId: string): Promise<Batch> {
    const response = await this.request.get(`/api/analyses/${batchId}`);
    expect(response.status()).toBe(200);
    const body: unknown = await response.json();
    return Batch.parse(body);
  }
}
