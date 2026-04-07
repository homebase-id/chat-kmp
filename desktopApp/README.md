# Homebase Desktop

## Requirements
Ensure install folder of the Java SDK has a jmods folder.

## Releasing

TODO

## Running in debug mode

To run:
```
./gradlew desktopApp:run --offline
```

To be able to package binaries on Ubuntu run these commands:
```
sudo apt-get install binutils
```
```
sudo apt-get install fakeroot
```

## Packaging

To package release binaries use these commands.

Linux:
```
./gradlew desktopApp:packageDeb
```
OSX:
```
./gradlew desktopApp:packageDmg
```
Windows:
```
gradlew desktopApp:packageMsi
```

To install built .deb file on linux:
```
sudo dpkg -i desktopApp/build/compose/binaries/main/deb/desktopApp_1.0.0-1_amd64.deb
```

## Build using Conveyor

Set version name and version code in build.gradle.kts

Install Conveyor on your system:
<https://conveyor.hydraulic.dev/18.0/>

Guide for JVM:
<https://conveyor.hydraulic.dev/13.0/tutorial/hare/jvm/>

Use command line from desktopApp module folder.
<https://conveyor.hydraulic.dev/4.0/running/#common-tasks>

## Release using Conveyor (dev)
Update version, ensure you increment since previously released version
Run scripts in desktopApp folder.

```
./gradlew createReleaseDistributable
export CONVEYOR_GITHUB_TOKEN="<github_pat>"
export SIGNING_KEY="<conveyor_singing_key>"
conveyor -f ci.conveyor.conf make copied-site
```

### Commands

Build proguard jars:
```
./gradlew createReleaseDistributable
```

Create a packaged app directory/bundle and execute your program from it:
```
conveyor run
```

Or build for specific platform: <https://conveyor.hydraulic.dev/8.0/tutorial/tortoise/3-compile/>
```
conveyor -Kapp.machines=mac.aarch64 make mac-app
```

### Test build
Build a download site for all available platforms in a directory called output:
```
conveyor make site
```

Serve the download site:
```
conveyor make site
cd output
npx serve -l 3001 .
```

Open http://localhost:3001/download.html and try installing your new app.

### Production build
Plug in USB with signing key.
Build a download site for all available platforms in a directory called output:
```
conveyor --file=conveyor_production.conf make site
```