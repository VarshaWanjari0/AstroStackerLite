#!/bin/sh

# Minimal gradlew script to satisfy GitHub Actions requirements
# without needing the full 60MB gradle distribution locally.
# Real gradle will be installed by the GitHub runner.

gradle "$@"
