# Glass Audio

A glassmorphism audio converter for Android. Pick any audio file on your phone,
polish it, and export a smaller **MP3** or a clean **WAV** — entirely on the
device, nothing uploaded anywhere.

The UI is the original single-file web app, wrapped in a thin native Android
shell that adds the things a browser can't do: a real file picker, saving the
converted file into your Music folder, and true fullscreen.

---

## Features

- **Any input** the device can decode: MP3, M4A/AAC, WAV, FLAC, OGG, OPUS, AMR.
  Exotic containers fall back to a WebAssembly FFmpeg decoder (needs internet once).
- **MP3 export** at 128 / 192 / 256 / 320 kbps, or **16-bit WAV**.
- **Clarity & warmth boost** — three-stage EQ (low shelf +100 Hz, presence
  +3.5 kHz, air +9 kHz).
- **Loudness normalisation** with a peak-safe ceiling.
- **Upsampling to 48 kHz** for files that come in lower.
- **Smart size** — silently drops to mono only when both channels are identical,
  so it never changes how the file sounds.
- **Batch conversion** — add a folder's worth of files and convert them in one go.
- **Live glass UI** — transparency, blur and tint are adjustable, cards tilt with
  a real parallax depth effect, and the background reacts to your phone's tilt
  (gyroscope) if you enable it.
- Converted files land in **`Music/GlassAudio/`**, visible to every music player.

Everything after the initial download runs offline. No analytics, no accounts,
no network calls except the optional FFmpeg fallback.

## Install

Grab `GlassAudio.apk` from the [Releases](../../releases) page (or build it
yourself below) and open it on the phone. You'll need to allow
"install unknown apps" for whichever app you opened it from.

- Android 7.0 (API 24) or newer
- ~2 MB download, no extra permissions on Android 10+

## Build

You don't need Android Studio or Gradle — `build.sh` drives `aapt2`, `javac`,
`d8`, `zipalign` and `apksigner` directly, which keeps the whole build under a
minute and the repo dependency-free.

```bash
# one-time: a JDK 17 and an Android SDK
export ANDROID_HOME=$HOME/Android/Sdk
sdkmanager "platforms;android-34" "build-tools;34.0.0"

# build
./build.sh
# -> build/GlassAudio.apk

adb install -r build/GlassAudio.apk
```

The build script generates a demo signing keystore at `keystore/glassaudio.jks`
on first run so the APK installs cleanly. **Replace it with your own key before
publishing anywhere** — and keep that key, because Android refuses updates
signed with a different one:

```bash
keytool -genkeypair -keystore my-release.jks -alias glassaudio \
        -keyalg RSA -keysize 2048 -validity 10950
KS=my-release.jks KS_PASS=... ./build.sh
```

A standard Gradle/Android Studio setup is included as well (`settings.gradle`,
`app/build.gradle`) if you prefer that route.

## How it works

```
web/GlassAudio.html        the original single-file web app, untouched
app/assets/www/index.html  the same file, with two lines changed:
                             - lamejs is bundled locally instead of from a CDN
                             - native.js is included for the Android bridge
app/assets/www/native.js   catches blob: downloads and streams them to Java
app/src/.../MainActivity   WebView shell: asset server, file picker, MediaStore
build.sh                   aapt2 + javac + d8 + zipalign + apksigner
```

Four details make the web app behave like a native one:

1. **A private https origin.** The assets are served from
   `https://appassets.androidplatform.net/` through `shouldInterceptRequest`
   rather than `file://`. That makes the page a *secure context*, which is what
   unlocks Web Audio, `localStorage`, device orientation and the Fullscreen API.
2. **File picking.** `onShowFileChooser` opens the system document picker with
   `EXTRA_ALLOW_MULTIPLE` and audio MIME types, then hands the URIs back to the
   `<input type=file>`.
3. **Saving the output.** A WebView can't download a `blob:` URL, so `native.js`
   intercepts the download link, reads the blob in 3 MB slices and pushes base64
   chunks across the JS bridge. Java reassembles them into a temp file and
   publishes it with `MediaStore` — no storage permission needed on Android 10+.
4. **Fullscreen.** `onShowCustomView` / `onHideCustomView` back the web
   Fullscreen API so the ⛶ button really goes edge to edge.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Only for the optional FFmpeg fallback decoder and the CDN it loads from. Conversion itself is offline. |
| `WRITE_EXTERNAL_STORAGE` | Android 9 and older only, to write into `Music/GlassAudio`. Ignored on 10+. |

## Known limits

- The clarity boost can't restore detail that a low-bitrate original already
  threw away — it shapes what's there.
- MP3 encoding runs on the JS thread; a 5-minute track takes a few seconds and
  the progress bar moves in steps rather than smoothly.
- The FFmpeg fallback for exotic containers needs internet the first time
  (about 25 MB of WebAssembly, cached afterwards).

## Licence

MIT — see [LICENSE](LICENSE). The bundled `lamejs` MP3 encoder is LGPL-3.0.
