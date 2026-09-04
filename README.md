# ShowHow

An expert does a job once and narrates it in Hindi or Marathi. The phone turns
that into a step-by-step guide with a photo and a voice clip per step. Anyone
else can then run the guide hands-free. Everything runs on the device.

## Hard constraints

1. **No `INTERNET` permission.** The manifest declares only `CAMERA` and
   `RECORD_AUDIO`, and strips `INTERNET` / `ACCESS_NETWORK_STATE` with
   `tools:node="remove"` if a dependency tries to merge them in.
2. **The step cutter is a pause detector plus a word list.** No video model,
   no ML in that path.
3. **The mode engine contains no AI.** Plain rules, decides in under 10 ms.
4. **Warnings advise, they never block.**

## Layout

    core/     Pure Kotlin. ZERO android.* imports. JVM-testable in seconds.
    capture/  AudioRecorder, CameraController, MotionSource
    ai/       Interfaces and Fake implementations (real ones land behind them)
    data/     Guide, Step, GuideStore, PolicyRepository
    ui/       Compose screens and theme

## Tuning without compiling

Every number lives in `policy.json`. On first run the app copies
`assets/policy.json` into `filesDir/policy.json` and from then on reads only
that file, watching it with a `FileObserver`. Push a new one and the app
retunes live:

```
adb push policy.json /data/local/tmp/
adb shell run-as com.showhow cp /data/local/tmp/policy.json files/policy.json
```

A malformed file is logged and ignored; the last good policy stays in force.

## Build

Needs JDK 17 or newer (verified on Temurin 25 and on Android Studio's bundled
JBR 21). `local.properties` is gitignored -- Android Studio writes it on first
open, or create it yourself with `sdk.dir=<path to your Android SDK>`.

```
./gradlew test          # core logic, JVM, seconds
./gradlew build         # both variants, plus lint
```

The two claims worth re-checking whenever a dependency changes:

```
# 1. no network permission survived the merge
grep -o 'android.permission[^"]*' app/build/intermediates/merged_manifest/*/*/AndroidManifest.xml | sort -u
# 2. core/ is still pure Kotlin
grep -rn '^import android' app/src/main/java/com/showhow/core/
```
