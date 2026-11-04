import { fireEvent, render, screen, within } from "@testing-library/react";
import type { ComponentProps } from "react";
import { describe, expect, it } from "vitest";
import { RecordingTable } from "@/RecordingTable";
import { parseRecordings } from "@/recordings";
import { rawRecording } from "./fixtures";

type Props = ComponentProps<typeof RecordingTable>;

const recordings = parseRecordings([rawRecording]);
const stereo = parseRecordings([{ ...rawRecording, channelCount: 2 }]);

function table(
  shown: typeof recordings,
  selections: Props["selections"] = [],
  onChange: Props["onChange"] = () => undefined,
) {
  return (
    <RecordingTable
      recordings={shown}
      selections={selections}
      onChange={onChange}
    />
  );
}

describe("RecordingTable", () => {
  it("shows one row per recording with units", () => {
    render(table(recordings));
    const row = screen.getByRole("row", { name: /dc-mono/ });
    expect(within(row).getByText("48 kHz")).toBeTruthy();
    expect(within(row).getByText("8.0 KiB")).toBeTruthy();
    expect(within(row).getByText("4096")).toBeTruthy();
  });

  it("states that the catalogue is empty instead of showing an empty table", () => {
    render(table([]));
    expect(screen.getByText("No recordings are registered.")).toBeTruthy();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("selects a recording with all its channels, then lets one channel go", () => {
    const changes: Parameters<Props["onChange"]>[0][] = [];
    const record = (selections: (typeof changes)[number]) =>
      changes.push(selections);
    const { rerender } = render(table(stereo, [], record));

    fireEvent.click(screen.getByRole("checkbox", { name: "Analyse dc-mono" }));
    expect(changes.at(-1)).toEqual([
      { recordingId: stereo[0]?.recordingId, channels: [0, 1] },
    ]);

    rerender(table(stereo, changes.at(-1), record));
    fireEvent.click(
      screen.getByRole("checkbox", { name: "Channel 0 of dc-mono" }),
    );
    expect(changes.at(-1)).toEqual([
      { recordingId: stereo[0]?.recordingId, channels: [1] },
    ]);

    rerender(table(stereo, changes.at(-1), record));
    fireEvent.click(
      screen.getByRole("checkbox", { name: "Channel 1 of dc-mono" }),
    );
    expect(changes.at(-1)).toEqual([]);
  });

  it("disables the channel choices of a recording that is not selected", () => {
    render(table(recordings));
    expect(
      screen
        .getByRole("checkbox", { name: "Channel 0 of dc-mono" })
        .hasAttribute("disabled"),
    ).toBe(true);
  });
});
