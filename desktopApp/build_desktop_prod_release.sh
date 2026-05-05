#!/bin/sh

./gradlew clean createReleaseDistributable

rm -r output
#conveyor --cache-limit=20.0 make site
conveyor --file=conveyor_production.conf --cache-limit=20.0 -Kapp.machines=mac.aarch64 make mac-app --overwrite
#conveyor --cache-limit=20.0 -Kapp.machines=linux.aarch64 make debian-package --overwrite
#conveyor --cache-limit=20.0 -Kapp.machines=linux.amd64 make debian-package --overwrite
#conveyor --cache-limit=20.0 make windows-msix --overwrite
#conveyor make rendered-icons



