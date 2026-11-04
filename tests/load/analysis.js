import http from "k6/http";
import { check, fail, sleep } from "k6";
import { Trend } from "k6/metrics";

const batchLatency = new Trend("batch_latency", true);
const POLL_SECONDS = 0.1;
const MAX_POLLS = 600;

const base = __ENV.BASE_URL || `http://127.0.0.1:${__ENV.WEB_PORT || 4173}`;

export const options = {
  vus: Number(__ENV.VUS || 1),
  duration: __ENV.DURATION || "30s",
  thresholds: {
    checks: ["rate==1"],
  },
};

export function setup() {
  const response = http.get(`${base}/api/recordings`);
  if (response.status !== 200) {
    fail(`Catalogue request returned ${response.status}`);
  }
  const recordings = response.json();
  if (recordings.length === 0) {
    fail("The catalogue is empty; run npm run fixtures and restart the stack");
  }
  return recordings.map((recording) => ({
    recordingId: recording.recordingId,
    channels: Array.from({ length: recording.channelCount }, (_, index) => index),
  }));
}

export default function (recordings) {
  const recording = recordings[Math.floor(Math.random() * recordings.length)];
  const started = Date.now();
  const response = http.post(
    `${base}/api/analyses`,
    JSON.stringify({
      contractVersion: "1",
      profileId: "pcm16-48k-hann4096-v1",
      items: [
        {
          itemId: crypto.randomUUID(),
          recordingId: recording.recordingId,
          channels: recording.channels,
        },
      ],
    }),
    { headers: { "Content-Type": "application/json" } },
  );
  const accepted = check(response, {
    "status is 202": (r) => r.status === 202,
    "batch is Queued": (r) => r.status === 202 && r.json("state") === "Queued",
  });
  if (!accepted) {
    return;
  }
  const batchId = response.json("batchId");
  for (let poll = 0; poll < MAX_POLLS; poll++) {
    const batch = http.get(`${base}/api/analyses/${batchId}`);
    const state = batch.json("state");
    if (state !== "Queued" && state !== "Running") {
      batchLatency.add(Date.now() - started);
      check(batch, { "batch is Completed": () => state === "Completed" });
      return;
    }
    sleep(POLL_SECONDS);
  }
  check(null, { "batch reached a terminal state": () => false });
}
