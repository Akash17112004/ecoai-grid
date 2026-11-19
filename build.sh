#!/usr/bin/env bash
# Builds ecoai-grid.jar with the plain JDK (17+). No Maven, no internet needed.
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -Xlint:all -d out $(find src/main/java -name "*.java" -not -path "*/ecoai/boot/*")
cp -r src/main/resources/* out/
jar --create --file ecoai-grid.jar --main-class ecoai.Main -C out .
echo "built ecoai-grid.jar"
