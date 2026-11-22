# AI Control Center

A native Android app (Kotlin, Jetpack Compose) that acts as a personal headquarters for
discovering, creating, managing and coordinating multiple AI agents on your phone.

The project is built in phases.

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the design.
- [docs/LIMITATIONS.md](docs/LIMITATIONS.md): what Android allows, and what each feature needs from you.
- [docs/STATUS.md](docs/STATUS.md): what works today and how it was verified.

## Build

Requires JDK 21 and the Android SDK (platform `android-37.2`).

```bash
./gradlew test                   # all unit tests (JVM and Robolectric)
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Every push builds an installable APK in GitHub Actions (workflow "Build and test", artifact
`AIControlCenter-APK`).

## Signing: read this before storing real API keys in the app

All builds are signed with the same key so that a newer build installs over an older one and
keeps your data. By default that is the development key in `keystore/`. **This repository is
public, so that key is public too.** Anyone could sign an APK that Android would accept as an
update of this app, and such an update could read the app's stored data. It still has to be
installed on your phone by you, but the signature alone proves nothing.

To sign with a key only you hold:

1. Create a keystore (any machine with a JDK):
   `keytool -genkeypair -keystore aicc.keystore -alias aicc -keyalg RSA -keysize 4096 -validity 10950`
2. Add four repository secrets (Settings > Secrets and variables > Actions):
   `AICC_KEYSTORE_BASE64` (output of `base64 -w0 aicc.keystore`), `AICC_KEYSTORE_PASSWORD`,
   `AICC_KEY_ALIAS`, `AICC_KEY_PASSWORD`.
3. Keep a copy of the keystore somewhere safe. If it is lost, later builds cannot update the
   installed app.

Android refuses to install an update signed with a different key, so switching keys means
uninstalling the app once, which deletes its data. Do the switch before you rely on the app.
