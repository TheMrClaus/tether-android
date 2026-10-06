# :baselineprofile (ta-gmyi)

Baseline Profile generator and startup macrobenchmark for `:app` (`com.android.test` module, targets
`:app`). Nothing here ships in the app. Both need a device or emulator on `adb`; no build ever does
(`automaticGenerationDuringBuild = false`, so `:app:assembleRelease`, which the release workflow runs on
a runner with no emulator, only packages a profile that is already committed).

## Where the profile lives

`app/src/release/generated/baselineProfiles/baseline-prof.txt` (the plugin's default; also
`startup-prof.txt`). Commit it. The release build packages it as `assets/dexopt/baseline.prof` and
`assets/dexopt/baseline.profm` in the APK; `androidx.profileinstaller` is on the runtime classpath so it
takes effect on a sideloaded install too. Until one is committed the build passes and ships no profile.

## Generate (on the emulator)

Inside the emulator `127.0.0.1` is the emulator itself, so the host's server is `10.0.2.2`. The
journeys sign in only when both arguments are passed; with neither, only cold start and the signed-out
screen are profiled. The app must be signed out (clear its data) before a signed-in run.

```
ANDROID_HOME=$HOME/Android/Sdk ./gradlew --no-daemon :app:generateReleaseBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.tether.serverUrl=http://10.0.2.2:4290 \
  -Pandroid.testInstrumentationRunnerArguments.tether.password=<the isolated server's password>
```

## Benchmark (cold start, 15 iterations, no profile vs baseline profile)

Run after the profile is committed (the `baselineProfile` test needs the APK to carry one and fails if
not). Same `benchmarkRelease` build, two compilation modes:

```
ANDROID_HOME=$HOME/Android/Sdk ./gradlew --no-daemon :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tether.baselineprofile.StartupBenchmark
```

Results (`timeToInitialDisplayMs`, `timeToFullDisplayMs` min/median/max for `noProfile` and
`baselineProfile`) land in `baselineprofile/build/outputs/connected_android_test_additional_output/`
and in the run's console.
