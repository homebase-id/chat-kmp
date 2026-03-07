# Homebase Chat

## Running

**Android**
```
./gradlew androidApp:installDebug
```

**iOS**
```
Run from Xcode
```
**Desktop**
```
./gradlew desktopApp:run
```

**Desktop with hot reload**
```
./gradlew desktopApp:hotRunJvm --auto
```

**Wasm**
```
./gradlew webApp:wasmJsBrowserDevelopmentRun --no-configuration-cache
```

## Release build

**Android**
Add signing key password to environment variable `HOMEBASE_KEYSTORE_PASS` first.
```
./gradlew androidApp:assembleRelease
```

**iOS**
Configure signing and user in Xcode. 

**Desktop**
```
./gradlew desktopApp:packageReleaseDeb
./gradlew desktopApp:packageReleaseMsi
./gradlew desktopApp:packageReleaseDmg
```

## Test

**Run all tests (JVM)**
Currently only tests found in common and api module.
```
./gradlew ./gradlew homebase-common:jvmTest homebase-api:jvmTest --rerun-tasks
```
To add to IDE as run option in menu, click "Edit configurations" in Run/debug menu, add gradle task, name it "AllTests" and paste below into run field:
```
homebase-common:jvmTest homebase-api:jvmTest --rerun-tasks
```