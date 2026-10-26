# InaiB: a lightweight, ad-blocking, India-tuned browser for Android

InaiB is a fictional browser. It renders with the **system Android WebView, which is Chrome's Chromium engine**, so pages behave like Chrome. The app itself is tiny: no third-party libraries, about 1 MB after shrinking.

## Build the APK

**Android Studio (easiest):** File > Open > this folder. Wait for Gradle sync, then Build > Build APK(s). Plug in a phone and press Run to install directly.

**Command line** (needs JDK 17 and the Android SDK):

```
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

For a release APK, use Build > Generate Signed App Bundle / APK in Android Studio and create a keystore. Keep that keystore safe.

If Android Studio says the Gradle plugin or SDK is out of date, accept its upgrade suggestion. The versions here are `AGP 8.7.3`, `Kotlin 2.0.21`, `compileSdk 35`.

## Features

- **Ad and tracker blocking:** host blocklist (subdomains included), third-party ad URL patterns, cosmetic CSS for leftover ad boxes, third-party cookies off, DNT and Global Privacy Control headers, popups blocked unless you tap, camera, mic and location denied by default.
- **India-tuned search:** Google, Bing, DuckDuckGo or Yahoo, each pinned to India. Results language: English, Hindi, Tamil, Telugu, Malayalam, Kannada, Bengali, Marathi (Menu > Search language).
- **Low-end phones (1-2 GB RAM):**
  - Only one WebView is alive at a time. Background tabs are stored as small saved states.
  - The WebView is released when the app is in the background and Android needs memory.
  - Blocklist stored as 8-byte hashes (100k domains is under 1 MB) and loaded off the main thread.
  - Data saver mode (no images), no media autoplay, caches trimmed on memory warnings.
  - Android 5.0+ (minSdk 21).
- **Everyday browser stuff:** tabs (max 10), downloads, file upload, fullscreen video, long-press link menu, desktop site, share, UPI/WhatsApp/tel/mail links open the right app, dark mode, can be set as default browser.

## Make the blocker stronger

The bundled `blocklist_hosts.txt` is a **small starter list**. Replace it with a big one:

```
sh scripts/update_blocklist.sh
```

Any hosts-format list URL works as an argument.

## Known limits (be aware)

- It is not full Chrome: no sync, extensions, password manager or Google sign-in (Google may refuse sign-in inside WebView-based browsers).
- Host-based blocking cannot remove ads that come from the same domain as the content, such as YouTube video ads or Google's own search ads. Brave handles those with an engine this project doesn't include.
- Camera/mic sites (video calls) are denied by default.
- No incognito mode yet (WebView shares one cookie jar).
- Not tested on real devices yet. Test on a 2 GB phone and tune `MAX_TABS` and the blocklist size.

## Ideas for next steps

Find in page, bookmarks and history, incognito (separate profile on Android 13+ WebView), per-site shield toggle, Hindi/Tamil UI strings, custom app icon via Image Asset Studio.
