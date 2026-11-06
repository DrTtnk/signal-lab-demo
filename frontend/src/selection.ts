import type { Selection } from "./analysis";

export function kibibytes(bytes: number): string {
  return `${(bytes / 1024).toFixed(1)} KiB`;
}

export function range(count: number): number[] {
  return Array.from({ length: count }, (_, index) => index);
}

export function selectChannels(
  selections: readonly Selection[],
  recordingId: string,
  channels: readonly number[],
): readonly Selection[] {
  const others = selections.filter(
    (selection) => selection.recordingId !== recordingId,
  );
  return channels.length === 0
    ? others
    : [...others, { recordingId, channels }];
}
