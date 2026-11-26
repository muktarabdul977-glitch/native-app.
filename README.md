# Native Android version of the Test News App

Same content, caching rules and measurement box as the PWA. The address of your PWA
(https://muktarabdul977-glitch.github.io/PWA/) is already set in the code.

## Build with GitHub (no Android Studio needed)
1. Create a NEW public repository (for example `native-app`) and upload everything in this folder.
   Drag the folders (not only loose files). If `.github/workflows/build.yml` is missing afterwards,
   use Add file > Create new file, name it `.github/workflows/build.yml` and paste the file's contents.
2. Open the Actions tab. "Build APK" starts by itself (or click Run workflow).
3. When it shows a green tick (3 to 10 minutes), open the run and download `TestNews-apk` under Artifacts.
   Unzip it to get `app-debug.apk`.
4. Copy the APK to the phone and install it (allow "install unknown apps" if asked).
If the build shows a red cross, copy the error lines from the failed step.

## Measurement box
Ready time (first title painted, from activity start), network body bytes at ready and now,
stored data (files + cache), APK size. Refreshes for 30 seconds. Tap it to hide it.

## Reset before a first-launch run
Settings > Apps > Test News > Storage > Clear data.
