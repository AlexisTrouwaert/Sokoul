# Windows Gradle builds

Use `./gradlew.bat :app:compileKotlin` and `powershell -NoProfile -File tools/build-tv.ps1 -q`.
Every Gradle build on Windows goes through this bootstrap automatically.

The bootstrap uses writable locations outside OneDrive:

| Purpose | Default |
| --- | --- |
| Gradle dependency cache | `%TEMP%/sokoul-gradle` |
| Project cache | `%TEMP%/sokoul-build/project-cache` |
| Build output | `<project-root>/build-output` |
| Android/JVM user home | `%TEMP%/sokoul-gradle-runtime` |
| Release APK | `<project-root>/build-output/tv/outputs/apk/release/tv-release.apk` |

Build outputs for the PC distribution and Android APK are produced from this project's Gradle modules under `build-output/`; no other Sokoul checkout is used. Dependency and project caches, Android user preferences and temporary signing material remain in `%TEMP%` to avoid duplicate dependency downloads and OneDrive cache churn. Set `SOKOUL_GRADLE_HOME` or `SOKOUL_BUILD_ROOT` for explicit alternative writable paths.
Set `ANDROID_HOME` when the Android SDK is outside `%LOCALAPPDATA%/Android/Sdk`.
Normal `GRADLE_OPTS` and Gradle arguments still apply. Environment preparation is scoped to the launcher process.

For private packages after workspace recovery, `python tools/export-private-build-keys.py --output <private-temp-file>` reads existing desktop provider settings without printing values. Set `SOKOUL_KEYS_FILE` to that file for the build. Add `--tv-backend http://<PC-LAN-IP>:25565` only for a separate TV key file; Windows retains its loopback server default. The exporter refuses source-checkout destinations and never overwrites an existing file. These packages contain private credentials and are only for the owner's devices.

For desktop packaging, the Windows bootstrap detects cloud-reparse libVLC DLLs and uses Node.js to materialize their bytes into ordinary temporary files automatically. An explicit `SOKOUL_VLC_DIR` takes precedence. `node tools/materialize-vlc.cjs` can also emit that temporary location for manual use. Every byte comes from this checkout, never another checkout.

Windows builds default to in-process Kotlin compilation, avoiding the separate Kotlin daemon's client marker files under the restricted LOCALAPPDATA directory. This also avoids a failed daemon startup followed by a fallback compilation on source changes.

When an existing debug key is available, the bootstrap copies it to the isolated signing directory and sets an explicit store path for Android Gradle Plugin. This preserves the signing identity and avoids AGP's default-keystore generation lock. Signing validation and APK signing remain enabled.

An already installed distribution of the exact wrapper version is reused from the configured cache or the user profile's `.gradle/wrapper/dists`. If none exists, the standard wrapper downloads the pinned distribution and requires network access. Cache removal therefore requires a future download. The bootstrap checks directory write access before starting Gradle, preserves a user's existing debug signing key when initializing the isolated Android home, and does not modify account permissions or machine configuration.
