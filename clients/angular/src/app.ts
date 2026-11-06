import { Component, computed, effect, signal } from "@angular/core";
import {
  buildRequest,
  fetchBatch,
  isTerminal,
  POLL_INTERVAL_MS,
  submitAnalysis,
  type AnalysisOutcome,
  type Selection,
} from "../../../frontend/src/analysis";
import {
  fetchRecordings,
  type RecordingsOutcome,
} from "../../../frontend/src/recordings";
import {
  kibibytes,
  range,
  selectChannels,
} from "../../../frontend/src/selection";
import {
  BAND_EDGES_HZ,
  format,
  spectrum,
} from "../../../frontend/src/result-format";

@Component({ selector: "signal-lab", templateUrl: "./app.html" })
export class App {
  readonly catalogue = signal<RecordingsOutcome | { status: "loading" }>({
    status: "loading",
  });
  readonly selections = signal<readonly Selection[]>([]);
  readonly analysis = signal<AnalysisOutcome | { status: "idle" | "running" }>({
    status: "idle",
  });
  readonly waiting = computed(() => {
    const analysis = this.analysis();
    return (
      analysis.status === "running" ||
      (analysis.status === "batch" && !isTerminal(analysis.batch))
    );
  });
  readonly names = computed(() => {
    const catalogue = this.catalogue();
    return new Map(
      catalogue.status === "ready"
        ? catalogue.recordings.map((recording) => [
            recording.recordingId,
            recording.name,
          ])
        : [],
    );
  });
  readonly range = range;
  readonly kibibytes = kibibytes;
  readonly format = format;
  readonly spectrum = spectrum;
  readonly bandEdges = BAND_EDGES_HZ;

  constructor() {
    void fetchRecordings()
      .then((value) => this.catalogue.set(value))
      .catch((error: unknown) => {
        this.catalogue.set(this.failure(error));
      });
    effect((cleanup) => {
      const analysis = this.analysis();
      if (analysis.status !== "batch" || isTerminal(analysis.batch)) return;
      const timer = setTimeout(() => {
        void fetchBatch(analysis.batch.batchId)
          .then((batch) => this.analysis.set({ status: "batch", batch }))
          .catch((error: unknown) => this.analysis.set(this.failure(error)));
      }, POLL_INTERVAL_MS);
      cleanup(() => clearTimeout(timer));
    });
  }

  channelsOf(id: string) {
    return this.selections().find((selection) => selection.recordingId === id)
      ?.channels;
  }

  setChannels(id: string, channels: readonly number[]) {
    this.selections.update((selections) =>
      selectChannels(selections, id, channels),
    );
  }

  toggleChannel(id: string, channel: number, checked: boolean) {
    const channels = this.channelsOf(id) ?? [];
    this.setChannels(
      id,
      checked
        ? [...channels, channel]
        : channels.filter((value) => value !== channel),
    );
  }

  analyse() {
    this.analysis.set({ status: "running" });
    void submitAnalysis(
      buildRequest(this.selections(), () => crypto.randomUUID()),
    )
      .then((outcome) => this.analysis.set(outcome))
      .catch((error: unknown) => this.analysis.set(this.failure(error)));
  }

  private failure(error: unknown) {
    return {
      status: "failed" as const,
      code: "invalid_response",
      message: error instanceof Error ? error.message : "Unknown failure",
    };
  }
}
