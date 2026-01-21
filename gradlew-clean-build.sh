#!/bin/bash
set -e

./gradlew --stop                                                        # Stopped corrupted daemons
./gradlew clean                                                         # Cleaned build artifacts
./gradlew build --no-configuration-cache --no-build-cache --rerun-tasks # Rebuilt without cache
