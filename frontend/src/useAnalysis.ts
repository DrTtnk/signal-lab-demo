import { useEffect, useState } from "react";
import {
  POLL_INTERVAL_MS,
  buildRequest,
  fetchBatch,
  isTerminal,
  submitAnalysis,
  type AnalysisOutcome,
  type Selection,
} from "./analysis";
import { fetchRecordings, type RecordingsOutcome } from "./recordings";

type Catalogue = { status: "loading" } | RecordingsOutcome;
type Analysis =
  | { status: "idle" }
  | { status: "running" }
  | AnalysisOutcome
  | { status: "failed"; code: string; message: string };

function describe(error: unknown): string {
  return error instanceof Error ? error.message : "Unknown failure";
}

export function useAnalysis(apiOrigin: string, newItemId: () => string) {
  const [catalogue, setCatalogue] = useState<Catalogue>({ status: "loading" });
  const [selections, setSelections] = useState<readonly Selection[]>([]);
  const [analysis, setAnalysis] = useState<Analysis>({ status: "idle" });

  useEffect(() => {
    fetchRecordings(apiOrigin)
      .then(setCatalogue)
      .catch((error: unknown) => {
        setCatalogue({
          status: "failed",
          code: "invalid_response",
          message: describe(error),
        });
      });
  }, [apiOrigin]);

  useEffect(() => {
    if (analysis.status !== "batch" || isTerminal(analysis.batch)) {
      return;
    }
    const timer = setTimeout(() => {
      fetchBatch(analysis.batch.batchId, apiOrigin)
        .then((batch) => {
          setAnalysis({ status: "batch", batch });
        })
        .catch((error: unknown) => {
          setAnalysis({
            status: "failed",
            code: "invalid_response",
            message: describe(error),
          });
        });
    }, POLL_INTERVAL_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [analysis, apiOrigin]);

  const waiting =
    analysis.status === "running" ||
    (analysis.status === "batch" && !isTerminal(analysis.batch));

  const analyse = () => {
    setAnalysis({ status: "running" });
    submitAnalysis(buildRequest(selections, newItemId), apiOrigin)
      .then(setAnalysis)
      .catch((error: unknown) => {
        setAnalysis({
          status: "failed",
          code: "invalid_response",
          message: describe(error),
        });
      });
  };

  const names = new Map(
    catalogue.status === "ready"
      ? catalogue.recordings.map((recording) => [
          recording.recordingId,
          recording.name,
        ])
      : [],
  );

  return {
    catalogue,
    selections,
    setSelections,
    analysis,
    waiting,
    analyse,
    names,
  };
}
