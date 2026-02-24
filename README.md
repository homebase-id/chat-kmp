# Homebase Chat

## How to run

### Android
```
./gradlew androidApp:installDebug
```

### iOS
```
Run from Xcode
```

### Desktop
```
./gradlew desktopApp:run
```

With hot reload:
```
./gradlew desktopApp:hotRunJvm --auto
```

### Wasm
```
./gradlew webApp:wasmJsBrowserDevelopmentRun --no-configuration-cache
```

## Build

### Android
```
./gradlew androidApp:assembleRelease
```