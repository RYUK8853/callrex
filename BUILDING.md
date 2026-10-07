# Building CallVault locally

1. `brew install --cask android-commandlinetools` (one-time)
2. Source the env: `source ~/.callvault-env.sh`
   - Sets JAVA_HOME (brew openjdk@17), ANDROID_HOME (~/Library/Android/sdk), PATH.
3. One-time SDK packages: `sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018" "cmake;3.22.1"`
   - The NDK and CMake build `libaudiohandoff.so`, the native half of Resilient recording. Without them the build fails at `externalNativeBuild`.
4. Build (from the repo root): `./gradlew assembleDebug`
5. APK: `app/build/outputs/apk/debug/app-debug.apk`

> Note: CallVault is reflection-heavy (hidden Android APIs, the privileged daemon is launched by class name), so a minified release build will break it. Keep `minifyEnabled` off for release builds.
