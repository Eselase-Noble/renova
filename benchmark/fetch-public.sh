#!/usr/bin/env bash
# Fetches the public open-source projects the targets suite migrates, at the commits it expects, next to the
# synthetic test apps. They are other people's code under their own licences and are not kept in this repository.
#
#   benchmark/fetch-public.sh [APPS_DIR]      # default: ../renova-test-apps
set -euo pipefail
apps="${1:-$(cd "$(dirname "$0")/../.." && pwd)/renova-test-apps}"
mkdir -p "$apps/public"

# Spring PetClinic (Apache-2.0): the commit before "Migrate to Spring Boot 3", on Spring Boot 2.7.3.
if [ ! -d "$apps/public/petclinic-boot27" ]; then
  clone="$(mktemp -d)"
  git clone -q https://github.com/spring-projects/spring-petclinic.git "$clone"
  mkdir -p "$apps/public/petclinic-boot27"
  git -C "$clone" archive 1315cf6e1f1e88e4850e5573388a89ba4469df07~1 | tar -x -C "$apps/public/petclinic-boot27"
  rm -rf "$clone"
  echo "Fetched $apps/public/petclinic-boot27"
else
  echo "Already there: $apps/public/petclinic-boot27"
fi
