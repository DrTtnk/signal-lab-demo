#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ "$#" -eq 0 ]; then
  set -- web application recordings
fi
for name in "$@"; do
  docker build --network host --file "docker/$name.Dockerfile" --tag "signal-lab-$name" .
done
