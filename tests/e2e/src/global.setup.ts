import { expect, test as setup } from "@playwright/test";
import { ApiClient } from "./api-client";
import { RECORDING_NAMES, saveTestData } from "./test-data";

setup(
  "setup — read the registered recordings and write the shared test data",
  async ({ request }) => {
    const recordings = await new ApiClient(request).recordings();

    expect(recordings.map((recording) => recording.name).sort()).toStrictEqual(
      [...RECORDING_NAMES].sort(),
    );
    const idOf = (name: string): string => {
      const found = recordings.find((recording) => recording.name === name);
      if (found === undefined) {
        throw new Error(`Recording ${name} is not registered`);
      }
      return found.recordingId;
    };
    saveTestData({
      recordings: {
        "zero-mono": idOf("zero-mono"),
        "dc-mono": idOf("dc-mono"),
        "nyquist-mono": idOf("nyquist-mono"),
        "quarter-cycle-mono": idOf("quarter-cycle-mono"),
        "stereo-with-tail": idOf("stereo-with-tail"),
      },
    });
  },
);
