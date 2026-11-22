# AI Control Center

A native Android app (Kotlin, Jetpack Compose) that acts as a personal headquarters for
discovering, creating, managing and coordinating multiple AI agents on your phone.

The project is being built in phases. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the
design and [docs/STATUS.md](docs/STATUS.md) for what works today.

## Build

Requires JDK 21 and the Android SDK (platform `android-37.2`).

```bash
./gradlew test                 # all unit tests (JVM and Robolectric)
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

Every push builds an installable APK in GitHub Actions (workflow "Build and test", artifact
`AIControlCenter-APK`). All builds are signed with the same key in `keystore/`, so a newer build
installs over an older one without losing data.
