> **Signal Lab is a demonstration, not a reference architecture.** It shows how Java, Python, Rust, React, PostgreSQL and RabbitMQ can work together in one system. It runs on Docker Compose and on a local Kubernetes cluster. The goal is to show how these technologies interact.
>
> The author would not use this design for a production system. The workload is small. Four languages and five services add operating cost and failure modes that a production system should avoid. A real system should use the simplest architecture that meets its requirements.

# Minimal getting started

## Install these tools first

- Node 24.21 with npm 11
- Java 25 with Maven 3.9
- Rust and Cargo 1.91
- uv 0.8.15
- Docker Engine with Compose

The setup command does not install system tools. Playwright installs its own Chromium.

## Prepare the workspace

1. Run `npm ci`.
2. Run `npm run setup`. This command installs the locked dependencies of each project with [Turborepo](https://turborepo.dev).
3. Copy `.env.example` to `.env`.
4. Run `npm run fixtures`. This command writes the five small recordings to `.fixtures/`. The recordings service mounts this folder read-only.

Each project owns its tool configuration:

| Project          | Tools                                                         |
| ---------------- | ------------------------------------------------------------- |
| `backend/java`   | Maven (`.mvn`)                                                |
| `backend/python` | uv, Ruff, Pyright, Pytest                                     |
| `backend/rust`   | Cargo                                                         |
| `tools/fixtures` | uv. Fixture generator and SciPy reference. Outside the stack. |
| `frontend`       | npm                                                           |
| `tests/e2e`      | npm                                                           |

## Read the documents

- [Functional design](docs/FD.md)
- [Acceptance status](docs/AC.md)
- [Shared contracts](docs/contracts.md)
- [Numerical profile](docs/numerics.md)

# How to run tests

Docker must run. The recordings service tests and the Spring tests start a real PostgreSQL with Testcontainers.

## Unit, lint and type checks

Run `npm run check`. It does these tasks in all projects:

- format, lint and type checks
- unit tests, and the frontend build
- the `tools/fixtures` tests, which run the real Rust engine against the SciPy reference
- a smoke run of the Rust benchmarks (each criterion benchmark runs once and measures nothing)

Turborepo caches `check` and `build` for each project. It skips projects that did not change. The Java check also builds the recordings image and writes the fixtures first.

## End-to-end tests on Docker Compose

1. Run `npm run setup:browser` once.
2. Run `npm run e2e`.

The command removes the volumes, builds the images, waits until the services are healthy, and runs the browser journey, the outage tests and the restart test. It stops the stack when it ends. The rules are in the [end-to-end test guide](docs/e2e.md).

## End-to-end tests on Kubernetes

1. Start minikube and the chart with `deploy/minikube.sh up`. It needs the three images (see the next section).
2. Run `deploy/minikube.sh e2e`.

## Benchmarks

A real timing run needs an idle machine. Use `npm run load` for the k6 run. The criterion commands are in [benchmarks](docs/benchmarks.md).

# How to build/deploy

## Build local artifacts

Run `npm run build`.

## Run the local Compose stack

1. Run `docker/build.sh`. It builds the three application images on the host network. The default build network fails DNS lookups on some machines, and Compose 5 cannot use the host network.
2. Run `docker compose --env-file .env -f docker/compose.yaml up --wait`. This starts web, application, recordings, PostgreSQL and RabbitMQ. All ports are on the loopback interface only.
3. To stop the stack, run `docker compose --env-file .env -f docker/compose.yaml down`. The database volume stays.

To remove the database volume too, add `--volumes` to the `down` command. Do this once after you pull a change to `docker/database/init.sh`. PostgreSQL runs init scripts only on an empty volume.

## Run the local Kubernetes target

1. Run `docker/build.sh`.
2. Run `deploy/minikube.sh up`. It creates the minikube profile `signal-lab`, loads the images, copies the fixtures to the node and installs the Helm chart.

Other commands:

- `deploy/minikube.sh stop` stops the cluster.
- `deploy/minikube.sh down` removes the release and the profile.

The script uses its own kubeconfig, `.kube/config` in this directory. It never reads or writes `~/.kube/config`. The `.envrc` file makes a plain `kubectl` in this directory use the same file. For direnv, run `direnv allow` once.

## Public deployment

No public deployment is configured.
