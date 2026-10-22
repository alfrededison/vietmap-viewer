# CLAUDE.md

Guidance for Claude Code working in this repo. User-facing text in the app and conversation with the owner (Hieu) is **Vietnamese**; code, identifiers and comments may be English or Vietnamese (existing comments are Vietnamese — keep that style).

## What this is

VM Viewer — a native Android app (Kotlin + Jetpack Compose) that replaces the official *Vietmap REC* app for browsing, streaming and downloading recordings from a **Vietmap C61 Pro** dashcam over its WiFi AP. The camera protocol was reverse-engineered from Vietmap REC 3.9.3 (`com.blackview.dashcam.vietmap`, a rebranded Blackview/Azdome app).

## Commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS; any JDK 17+
./gradlew assembleDebug
./gradlew assembleRelease          # R8 minify + shrink, signed with debug key
./gradlew installDebug
adb logcat --pid=$(adb shell pidof -s com.hieu.vmviewer)
```

There are no tests yet. When adding them, put pure-JVM tests (XML parsing, URL building) in `app/src/test`.

## Stack / versions

AGP 8.7.3 · Gradle 8.14.3 · Kotlin 2.0.21 (compose compiler plugin) · compileSdk/targetSdk 35 · **minSdk 29** (relies on `MediaStore.RELATIVE_PATH`, scoped storage, no storage permission) · Compose BOM 2024.12.01 · Media3 1.5.1 · OkHttp 4.12 · Coil 2.7 · coroutines 1.9. Versions are inline in `app/build.gradle.kts` (no version catalog).

## Architecture

Single activity, no navigation library, no DI. Package `com.hieu.vmviewer`:

```
App.kt              Application: shared OkHttpClient, appScope (outlives VM), Coil ImageLoader (1 concurrent request)
MainActivity.kt     Theme + trivial nav (list ↔ player via rememberSaveable fpath); onStart/onStop → VM fore/background
MainViewModel.kt    ConnState machine, file list, heartbeat, delete, resumeRecording
cam/CamApi.kt       Novatek HTTP commands + CamFile model + XML parsing (XmlPullParser)
cam/CamNetwork.kt   requestNetwork(WiFi, no INTERNET cap) + bindProcessToNetwork
cam/DownloadQueue.kt  Sequential downloads, Range resume, MediaStore publish to Movies/VMViewer
ui/FileListScreen.kt  Tabs (Video / Sự kiện), sticky date headers, rows with thumb/progress/actions
ui/PlayerScreen.kt    ExoPlayer + OkHttpDataSource, tuned DefaultLoadControl, PlayerView via AndroidView
```

Flow: `CamNetwork` reports WiFi network → (if foreground) `connect()` → `ping` (cmd 3012) → `enterPlayback` (2001 par=0, 3001 par=2) → wait 800 ms → `listFiles` (3015, retried once after 1.5 s if empty) → `Ready` + heartbeat (3016 every 10 s).

### Invariants — do not break

1. **Dashcam safety.** The camera does NOT record while in playback mode. Every exit path must call `resumeRecording()` (3001 par=1, then 2001 par=1): `onStop` (unless downloads are running, then `DownloadQueue.onIdle` does it), the "Ghi hình" button. Any new feature that keeps the camera in playback mode must preserve this.
2. **Process is bound to the camera WiFi.** All HTTP (OkHttp, ExoPlayer, Coil) goes through the bound network. Don't create clients that bypass `App.http` with a different socket factory, and don't call internet hosts while bound (there is no internet on the camera AP).
3. **Be gentle with the camera's HTTP server.** It is single-threaded-ish and slow. Thumbnails: max 1 concurrent request. Downloads: strictly sequential (Mutex). Never fire parallel requests in loops.
4. **Bandwidth ceiling ~25 Mbps vs ~20 Mbps video.** Playback faster than 1x over WiFi stalls (a 2x/4x speed button was removed for that reason). Local (downloaded) playback has no such limit.
5. Cleartext HTTP is allowed only for `192.168.1.254` (`res/xml/network_security_config.xml`).

## Camera protocol (Novatek, base `http://192.168.1.254`)

All commands: `GET /?custom=1&cmd=<n>[&par=<int>][&str=<string>]`. Reply is XML:

```xml
<Function><Cmd>3001</Cmd><Status>0</Status></Function>
```

`Status` 0 = OK, negative = error (`-21` seen for 3014/3031 while in playback mode; `-22` for 2001 par=0 when not recording — harmless).

| cmd | par / str | Meaning | Notes |
|---|---|---|---|
| 2001 | par 0/1 | stop / start recording | |
| 2002 | par = index | set resolution | index from 3030 |
| 2015 | par 0/1 | live view stream off/on | used by LiveNovaActivity |
| 3001 | par 1 / 2 (0 = photo) | mode: movie / **playback** | must be 2 before 3015 |
| 3012 | — | about / firmware | used as ping |
| 3014 | — | current values of all settings | only in movie mode |
| 3015 | — | file list (XML below) | only in playback mode |
| 3016 | — | heartbeat | app gốc gọi định kỳ |
| 3024 | — | SD card status | |
| 3030 | — | supported resolutions | see below |
| 3031 | str=`all` | all settings + options | movie mode; not yet tested OK |
| 4001 | appended to file URL | JPEG thumbnail: `<fileUrl>?custom=1&cmd=4001` | |
| 4003 | str = FPATH | delete one file | |
| 6801 | par | video codec H.264/H.265 (some models) | support on C61 Pro unconfirmed |

Other setting ids (2003 loop length, 2004 HDR, 2006 motion detect, 2011 G-sensor, 6802 parking, 8901–8924 speed-camera/EDOG warnings…) are in the decompiled `cn/com/blackview/azdome/constant/Constant.java` (`Constant.Cmd`).

File list (3015):

```xml
<LIST>
  <ALLFile><File>
    <NAME>2026_1020_210335_908.MP4</NAME>
    <FPATH>A:\VIETMAP\Movie\2026_1020_210335_908.MP4</FPATH>
    <SIZE>141717200</SIZE>
    <TIMECODE>1565829262</TIMECODE>
    <TIME>2026/10/20 21:04:28</TIME>
    <ATTR>32</ATTR>
  </File></ALLFile>
  ...
</LIST>
```

- Download/stream URL = `http://192.168.1.254` + FPATH without `A:` and with `\` → `/` (e.g. `/VIETMAP/Movie/x.MP4`). **Do not hard-code `CARDV/MOVIE`** (generic Novatek path) — Vietmap uses `VIETMAP/Movie`.
- Event (locked) clips live in `...\Movie\RO\`, `ATTR` 33 (32 = normal). Treat `\RO\` as "Sự kiện".
- Live view: `rtsp://192.168.1.254/xxx.mov` or `http://192.168.1.254:8192` (not implemented).
- Camera also exposes a HiSilicon-style CGI at `http://192.168.1.254/cgi-bin/hisnet/` for EDOG/speed-camera data (`getcommparam.cgi?-type=EDOG_VERSION_INFO`, upload) — not used yet.

### Measured on the owner's C61 Pro

- Recording: **2560×1440 @30 fps, H.264 High, ~20 Mbps**, AAC 96 kbps → ~9 GB/hour.
- Resolutions (3030): `0` 2880×2160P24 · `1` 2560×1440P30 (current) · `2` 1080P60 · `3` 1080P30 · `5` 720P60 · `6` 720P30 · `7` 1080P30 + VGA (rear cam).
- WiFi throughput ~3.0–3.1 MB/s (measured with curl from a Mac).
- Range-request support on the camera's HTTP server: **not yet verified** (affects seek + download resume; `DownloadQueue` falls back to full restart on 200).

## Reverse-engineering notes

- Decompile: `jadx -d out --no-res app.apk`. Relevant code is in `cn/com/blackview/azdome/` (`api/Api.java` Retrofit interface, `constant/Constant.java`, `ui/activity/domestic/LiveNovaActivity.java` for the Vietmap/Novatek flow incl. `openGalleryAfterTfCardCheck`, `persenter/cam/child/tabs/*` for file list, `com/blackview/common_download/downdialog/ProgressDownloader.java` for the original downloader).
- The app supports several chipsets: Novatek (`192.168.1.254`, `?custom=1&cmd=`), HiSilicon (`192.168.0.1/cgi-bin/hisnet/*.cgi`), Mstar/SigmaStar (`192.72.1.1/cgi-bin/Config.cgi`), and others. C61 Pro is detected via SSID containing `VM_C61Pro` → Novatek.
- Some models have 2.4G/5G WiFi (SSID suffix `_5G`); unknown for C61 Pro.

## Known gaps / TODO

- [ ] Verify on device: Range support (seek + resume), real download speed on phone, thumbnail endpoint behaviour.
- [ ] Downloads run in `appScope`, not a foreground service → may be killed in background (and leave camera in playback mode). Consider a foreground `Service`/WorkManager with notification.
- [ ] Settings screen (3014/3031 read, 2002 resolution, 6801 codec if supported, HDR, G-sensor sensitivity).
- [ ] Live view (RTSP via Media3 `media3-exoplayer-rtsp`).
- [ ] Multi-select download/delete; filter by time range; storage usage summary (3024).
- [ ] GPS/speed overlay if the MP4 contains a GPS track (check for a data stream / `gpmd`-like box).
- [ ] Proper release signing config (currently debug key).
- [ ] Unit tests for `CamApi` XML parsing and `CamFile.url`.

## Conventions

- Blocking network calls live in `CamApi` and are always called from `Dispatchers.IO`.
- UI state is a single `StateFlow<UiState>` in `MainViewModel`; download state is a separate `StateFlow<Map<fpath, Dl>>`.
- Keys for files are `fpath` (unique across folders), not `name`.
- Keep the dependency set small; no Hilt/Navigation unless the app grows substantially.
