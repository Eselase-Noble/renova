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

# The same project as a Gradle-only build: PetClinic ships both, and Renova uses Maven where there is a pom.xml.
if [ ! -d "$apps/public/petclinic-boot27-gradle" ]; then
  cp -r "$apps/public/petclinic-boot27" "$apps/public/petclinic-boot27-gradle"
  rm -rf "$apps/public/petclinic-boot27-gradle/pom.xml" "$apps/public/petclinic-boot27-gradle/mvnw" \
         "$apps/public/petclinic-boot27-gradle/mvnw.cmd" "$apps/public/petclinic-boot27-gradle/.mvn"
  echo "Made $apps/public/petclinic-boot27-gradle"
fi

# Spring PetClinic Microservices (Apache-2.0) at its Spring Boot 2.6 release: seven Maven modules on Spring
# Cloud 2021, with a Maven wrapper from 2018. Not part of the targets suite: its API gateway does not finish
# without AI (see docs/verified-migrations.md).
if [ ! -d "$apps/public/petclinic-microservices-boot26" ]; then
  clone="$(mktemp -d)"
  git clone -q https://github.com/spring-petclinic/spring-petclinic-microservices.git "$clone"
  mkdir -p "$apps/public/petclinic-microservices-boot26"
  git -C "$clone" archive v2.6.7 | tar -x -C "$apps/public/petclinic-microservices-boot26"
  rm -rf "$clone"
  echo "Fetched $apps/public/petclinic-microservices-boot26"
else
  echo "Already there: $apps/public/petclinic-microservices-boot26"
fi

# Stateless (Apache-2.0), the .NET state-machine library, at a commit of February 2016: a portable class library
# and its tests on NUnit 2.4 kept as a file, all in the project format before the .NET SDK. The folder of
# documentation tools (Resource, binaries) is left out.
if [ ! -d "$apps/public/stateless-2016" ]; then
  clone="$(mktemp -d)"
  git clone -q https://github.com/dotnet-state-machine/stateless.git "$clone"
  mkdir -p "$apps/public/stateless-2016"
  git -C "$clone" archive bf4b5428637515b90bc4224227832988649bae95 | tar -x -C "$apps/public/stateless-2016"
  rm -rf "$clone" "$apps/public/stateless-2016/Resource"
  echo "Fetched $apps/public/stateless-2016"
else
  echo "Already there: $apps/public/stateless-2016"
fi
