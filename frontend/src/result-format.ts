import type { SucceededItem } from "./analysis";

export const BAND_EDGES_HZ = [0, 1500, 6000, 24000] as const;
export type Channel = SucceededItem["result"]["channels"][number];

export function format(value: number): string {
  return String(Number(value.toPrecision(6)));
}

export function spectrum(channel: Channel) {
  const width = 600;
  const height = 160;
  const logs = channel.meanPsd.map((value) =>
    Math.log10(Math.max(value, 1e-20)),
  );
  const top = Math.max(...logs);
  const bottom = Math.min(top - 1, Math.min(...logs));
  const points = logs
    .map((value, bin) => {
      const x = (bin / (logs.length - 1)) * width;
      const y = height - ((value - bottom) / (top - bottom)) * height;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
  return { width, height, points };
}
