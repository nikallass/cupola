# Cupola

[![build](https://github.com/nikallass/cupola/actions/workflows/build.yml/badge.svg)](https://github.com/nikallass/cupola/actions/workflows/build.yml) · [Русский](README.md)

An offline Android visualizer for voice practice: spectrogram, note and deviation in cents, overtones and the "ring" — the singer's formant around 2.4–3.6 kHz. The ring is judged by the share of the voice's energy in the cupola band and by how far that band rises above the neighbouring frequencies, not by loudness. In game mode, holding the sound "in the cupola" earns points. No cloud, no telemetry: audio never leaves the device.

**Install:** the APK of the latest release is on the [Releases](https://github.com/nikallass/cupola/releases) page. You need Android 8.0+ and permission to install from unknown sources. Releases are signed with a permanent key, so new versions install over old ones.

**This is not a medical tool and not a substitute for a teacher.** The app shows what happened to the sound and gives no technical vocal instructions. If your throat is tired or hurts, stop practising regardless of the points.

Documents (in Russian): `SPEC.md` (specification; §15 — per-version decisions), `RESEARCH.md` (scientific background), `TICKETS.md` (work plan), `docs/device-notes.md` (what was checked on devices), `CHANGELOG.md`.

## Features

- **Note.** Russian and scientific names, deviation in cents on a ±50 ¢ scale, frequency, overtone count, vibrato / wobble / tremolo (the borders are adjustable). Readouts are smoothed, and the note holds through short detector slips.
- **Cupola.** The arc fills from both ends towards the apex; inside it is the energy share in the band, under it the rise in dB. When the halves meet, the apex sparkles, points are awarded and the haptic feedback fires.
- **Target and "Play the tone".** Pin a note with a tap or pick it from a list; "Play the tone" plays it with a piano-like sound. A4 tuning and a fine offset in cents are in the settings.
- **Spectrogram.** Logarithmic or linear axis, cupola band, pitch track, lines of the pinned note and its overtones, adjustable contrast. Pinch to change the time window from 2 to 30 s; while paused, the 60 s buffer scrolls back.
- **Spectrum.** A time-smoothed spectrum with harmonic lines and numbers and a background-noise line.
- **Background noise** is estimated automatically — from the quiet pauses between phrases over the last few minutes (3 by default) — and subtracted from the spectrum, so noise neither earns points nor hides the cupola.
- **Game mode.** A floating star button starts a game: points, streaks, hints, a summary; time and points show in the "Note" zone header. The game keeps running in the background with a notification. The mode can be turned off.
- **Interface.** Full screen; zones fold with a tap on their header; the floating pause and settings buttons fade while untouched. Portrait and landscape, phones and tablets, light and dark themes, Russian and English.
- **Diagnostics.** "Save log" in the advanced settings writes the app log to a file.

## Requirements

- Android 8.0+ (API 26). The development device is a KENSHI E11 tablet (Android 13).
- A microphone. The audio source is `UNPROCESSED` (when the device declares it), otherwise `VOICE_RECOGNITION`; noise suppression and AGC are never enabled. If the system hands out silence at first, the app reopens the microphone by itself.

## Build

Kotlin + Jetpack Compose, Gradle KTS, JDK 17, Android SDK 35 (platform 35, build-tools 35.0.0).

```
./gradlew :app:assembleDebug            # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease          # release without minification; signing — see "Release"
./gradlew :core-dsp:test                # DSP tests on synthetic signals (pure JVM)
./gradlew :core-dsp:test --tests "*.PitchTest"
./gradlew check                         # tests + a check that JVM modules do not import android.*
```

Modules: `:app` (UI, DI, session, service, haptics, settings), `:core-audio` (AudioRecord, ring buffer, engine), `:core-dsp` (FFT, pitch, metrics, background noise, scoring — pure JVM), `:core-notation` (notes, octaves, cents — pure JVM), `:core-testdata` (synthetic signals for tests).

### Edit → server build → tablet loop

The developer's machine is an arm64 VM for which AAPT2 is not shipped, so builds run on an x86_64 server and the APK is installed on the tablet over `adb` on the local network:

```
scripts/deploy.sh              # rsync → server → assembleDebug → APK into build/ → adb install → launch
scripts/deploy.sh --test       # JVM tests only (:core-dsp, :core-notation) on the server
scripts/deploy.sh --check      # gradlew check: tests + android.* guard for JVM modules + lint
scripts/deploy.sh --release    # assembleRelease → build/cupola-release.apk
scripts/deploy.sh --no-install # build and download the APK, do not install
scripts/deploy.sh --logcat     # show the app's logcat after launch
scripts/deploy.sh --test -- --tests "*.PitchTest"   # pass arguments through to Gradle
scripts/tap.sh "Старт"         # tap a UI element on the tablet (for autonomous checks)
scripts/tones.py build/tones   # test tones for the monitors (aplay build/tones/saw220.wav)
```

Server and tablet addresses come from the environment variables `CUPOLA_SERVER`, `CUPOLA_SSH_KEY`, `CUPOLA_REMOTE_DIR`, `CUPOLA_DEVICE` (defaults at the top of the script).

| Loop (warm Gradle daemon) | Time |
|---|---|
| edit → app running on the tablet | ~45–60 s |
| `--test` | ~10–50 s |
| `--check` | ~60 s |
| first run (downloading Gradle, SDK components, dependencies) | ~5 min |

## Release

GitHub Actions (`.github/workflows/build.yml`): on every push and PR — `gradlew check` (DSP tests, the `android.*` ban in JVM modules, lint) and a debug APK as an artifact; on a `v*` tag — a release APK and a GitHub Release with notes taken from `CHANGELOG.md`. To ship a version: bump `versionCode`/`versionName` in `app/build.gradle.kts`, add a `CHANGELOG.md` section, `git tag vX.Y.Z && git push --tags`.

Release signing: releases are signed with a permanent key from the repository secrets `CUPOLA_KEYSTORE_B64` (the keystore in base64), `CUPOLA_KEYSTORE_PASSWORD`, `CUPOLA_KEY_ALIAS`, `CUPOLA_KEY_PASSWORD`; without the secrets the runner's debug key is used (such an APK installs, but updating over a build signed with another key needs a reinstall). The keystore is created once: `keytool -genkeypair -v -keystore cupola.jks -alias cupola -keyalg RSA -keysize 4096 -validity 10000`, then `base64 -w0 cupola.jks` → secret. Locally, `./gradlew :app:assembleRelease` picks up the same environment variables.

## Known limitations (SPEC §13)

- The cupola thresholds (share %, rise dB) are the same for everyone and are tuned in the advanced settings; compare yourself only with yourself — the microphone, distance and room change the numbers.
- The singer's formant develops over months; no growth within a week is normal.
- A strong "ring" can also come from a pressed, tight sound; the gates catch this only partly. The final judges are a teacher and a sense of a free throat.
- Twang and nasal sound also raise the energy around 3 kHz; the app does not reliably tell them apart.
- Some devices lack `UNPROCESSED`, and `VOICE_RECOGNITION` is implemented with deviations; the settings have a source switch, and the processing state is explained under "Sound processing".
- The pitch detector is YIN with a harmonic-comb tracker: on recordings with an orchestra and through speakers, brief octave slips are possible. CPPS/HNR and related gates come in later versions.

## License

MIT, see `LICENSE`. Fonts Manrope and IBM Plex Mono — SIL Open Font License 1.1 (`app/fonts-licenses/`).

## Support the project

Cupola is open source: the code, builds and change history are on [GitHub](https://github.com/nikallass/cupola). If the app helps your practice, please support its development: [https://www.tbank.ru/cf/BcjzatrF9O](https://www.tbank.ru/cf/BcjzatrF9O).
