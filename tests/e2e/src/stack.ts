import { execFileSync } from "node:child_process";
import { expect } from "@playwright/test";
import { env } from "./env";

type Service = "application" | "recordings" | "rabbitmq" | "database" | "web";

function compose(...args: string[]): string {
  return execFileSync(
    "docker",
    [
      "compose",
      "--project-name",
      env.compose.project,
      "--env-file",
      env.compose.envFile,
      "-f",
      env.compose.file,
      ...args,
    ],
    { encoding: "utf8" },
  );
}

function notHealthy(): string[] {
  return compose(
    "ps",
    "--all",
    "--format",
    "{{.Service}} {{.State}} {{.Health}}",
  )
    .split("\n")
    .filter(
      (line) =>
        line.trim() !== "" &&
        line.trim().split(" ").slice(1).join(" ") !== "running healthy",
    )
    .sort();
}

export const stack = {
  stop(service: Service): void {
    compose("stop", service);
  },
  restart(service: Service): void {
    compose("restart", service);
  },
  async restore(): Promise<void> {
    compose("up", "--no-build", "-d");
    await expect
      .poll(() => notHealthy(), { timeout: 90_000, intervals: [1_000] })
      .toStrictEqual([]);
  },
  running(service: Service): boolean {
    return compose("ps", "--status", "running", "--services")
      .split("\n")
      .includes(service);
  },
};
