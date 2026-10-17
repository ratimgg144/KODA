# Build an Android APK without Android Studio

This project contains a GitHub Actions workflow that downloads the Android tools and compiles the app on GitHub's build computers. **The ZIP itself is not an APK.** The workflow has not been executed in this environment; a successful build is required before an APK exists.

1. Visit https://github.com/new and create a repository (private is fine).
2. Unzip this archive on your PC. Go into the `KODA-BMW-Android-v0.2` folder.
3. Upload **the contents of that folder**, including `.github/`, to your new repository at its root (GitHub > Add file > Upload files). Commit the files to `main`.
4. Open your repository's **Actions** tab, select **Build KODA Android APK**. It may start automatically after the push. Otherwise, select **Run workflow** and choose `main`.
5. Open the workflow run. If the job completed successfully (green check), download **KODA-v0.2-Android-APK** under **Artifacts**. Unzip the downloaded artifact; it contains `app-debug.apk`.
6. Transfer `app-debug.apk` to your Android phone and install from Files/Downloads. Enable installing from that source when Android asks.

Note: If the run fails, open the first red step to inspect the compilation error. Because this source wasn't built with a real Android SDK in the authoring environment, a first-build fix might still be needed. All 19 Java simulator tests passed, but that's different from a successful Android build or physical G20 test.

This is a read-only ENET prototype. It cannot code, flash, or alter BMW ECUs. Never test vehicle connectivity while driving.
