# Building and contributing

The app uses Kotlin and Jetpack Compose.

## Build requirements

- JDK 17.
- Android SDK with platform 35 and the required build tools.
- The included Gradle wrapper (Gradle 8.9).

Configure your own SDK location using `ANDROID_HOME` or an untracked
`local.properties` file. Do not commit local paths or signing credentials.

On Windows, run `gradlew.bat testDebugUnitTest assembleDebug` from the
project root. On Linux/macOS, run `sh gradlew testDebugUnitTest assembleDebug`.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
Release signing keys are not included. Do not distribute debug signing keys
or replace an existing release key without planning the update path.

## Test data and privacy

Test fixtures should contain synthetic values, not personal device captures.
Optional local BIN samples can be supplied through the `FIT3_*` environment
variables used by the tests. They are not part of this repository.

Do not commit Bluetooth captures, health databases, backups, notification text,
device identifiers, credentials, or private signing keys. Review logs before
attaching them to issues.

## Third-party resources

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Project publication
does not grant rights to third-party artwork or service content.
