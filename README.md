# Samsung NX1000 Wireless Photo Transfer (NX Cam Link)

[![Release](https://img.shields.io/badge/Release-v1.4.0-blue.svg)](release/NX-MobileLink-v1.4.0.apk)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2021--34)-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)
[![APK Size](https://img.shields.io/badge/APK%20Size-80%20KB-success.svg)](release/NX-MobileLink-v1.4.0.apk)

Modern, open-source Android application for wireless photo and video transfer from **Samsung NX1000** (and compatible Samsung NX series cameras) to modern Android smartphones running **Android 7.0 through Android 14+**.

---

## 📷 Overview & Background

Samsung discontinued official app support for its Smart Camera ecosystem years ago. The legacy **Samsung MobileLink** and **Samsung Remote Viewfinder** applications on the Google Play Store are incompatible with modern Android versions due to:
* Deprecated network APIs (lack of modern `NetworkCallback` and process socket binding).
* Strict cleartext HTTP restrictions in Android 9+.
* Changes in Android scoped storage permissions (Android 13+ `READ_MEDIA_IMAGES`).
* Aggressive Android Wi-Fi handoff (disconnecting from camera APs that have no internet access).

This project is a clean-room, reverse-engineered native Android solution that completely restores fast, stable, and seamless wireless photo transfer and remote viewfinder functionality.

---

## ✨ Features

- **Apple-Inspired Dark Mode Home Screen**: Clean, modern dark dashboard featuring an interactive Wi-Fi status capsule and modular cards for **MobileLink** and **Remote Viewfinder**.
- **100% Modular Protocol Architecture**: Completely separates MobileLink and Remote Viewfinder into independent clients and activities, preventing cross-protocol interference or playback engine deadlocks.
- **Pure UPnP/DLNA MobileLink Engine**: 
  - Speaks pure UPnP `ContentDirectory:1` with `SEC_DSC_<MAC>` identity.
  - Deep Recursive Folder Browsing (BFS) across multi-level SD card directories (`0` ➔ `Storage` ➔ `DCIM` ➔ `100_PHOTO`).
  - Automated 5-stage approval retry polling while the user presses 'Yes' on the camera LCD.
  - Global atomic download lock with silent keepalive suspension during downloads, preventing socket congestion and camera disconnects.
- **Dedicated Remote Viewfinder Engine**:
  - Full Mode Control integration on port 7788 (`/mode/control`) with `SEC_RVF_<MAC>` identity.
  - Dual-port reciprocal callback server (ports 7788 & 7792) to keep the camera LCD connected.
  - Continuous MJPEG streaming pipeline from port 7679 (`/livestream.avi`) without socket aborts.
  - Remote shutter trigger command.
- **Ultra-Lightweight & Native**: Zero bloated third-party dependencies, built entirely using native Android APIs. The compiled, signed APK is only **~80 KB**!

---

## 📲 Download & Installation

You can download the pre-built, signed APK directly from this repository:

👉 **[Download NX-MobileLink-v1.4.0.apk](release/NX-MobileLink-v1.4.0.apk)**

1. Transfer or download the APK to your Android device.
2. Open the file and allow "Install from Unknown Sources" if prompted.
3. Open **NX Cam Link**.

---

## 🔌 How to Use

### 1. Home Screen
When you launch the app, you will see the **NX Cam Link** dashboard:
* **Wi-Fi Status Capsule**: Shows current Wi-Fi SSID and status dot (tap to open Wi-Fi settings).
* **Card 1: MobileLink** (Green icon) ➔ Tap to browse and download photos/videos from SD card.
* **Card 2: Remote Viewfinder** (Blue icon) ➔ Tap for live video stream and remote shutter control.

### 2. MobileLink Mode (Transfer Photos & Videos)
1. On your Samsung NX camera, turn the mode dial to **Wi-Fi**.
2. Select **MobileLink** ➔ choose **"Select files on smartphone"** or **"All files"**.
3. **Important**: When the camera displays *"Allow connection from smartphone?"*, press **"Yes" (כן)** on the camera LCD.
4. In the app, tap the **MobileLink** card and press **"סרוק וחבר מצלמה"**.
5. Your photos load in a fast 3-column grid. Tap any photo for preview or select multiple files to download directly to your Android Gallery (`Downloads/NX1000`).

### 3. Remote Viewfinder Mode (Live Preview & Shutter)
1. Turn the camera mode dial to **Wi-Fi** and select **Remote Viewfinder**.
2. In the app, tap the **Remote Viewfinder** card and press **"התחבר למצלמה"**.
3. The camera displays "Connected to smartphone", live preview streams to your phone screen, and you can tap the circular shutter button to capture photos remotely!

---

## 🛠️ Building from Source

### Method A: One-Click PowerShell Build (Fastest, No Gradle Needed)
Requires Android SDK build-tools and JDK 17+:
```powershell
powershell -ExecutionPolicy Bypass -File build_apk.ps1
```
The output APK will be generated, zip-aligned, and signed with both v1 and v2 signature schemes in `release/NX-MobileLink-v1.4.0.apk`.

### Method B: Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `samsung-nx1000-photo-transfer` directory.
3. Allow Gradle to sync.
4. Click **Run** or **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

---

## 📋 Changelog

### Version 1.4.0
* **Modular Dashboard Architecture**: Introduced an Apple Dark Mode Home Screen with separate interactive cards for **MobileLink** and **Remote Viewfinder**, allowing modular development and maintenance without cross-feature bugs.
* **Complete Protocol Separation**:
  - `MobileLinkClient.java`: Pure UPnP/DLNA with zero video stream probes or port 7788/7679 interference, permanently fixing camera freezes in MobileLink mode.
  - `ViewfinderClient.java`: Dedicated Remote Viewfinder client with continuous MJPEG stream reader and Mode Control keepalive, fixing the "Network disconnected" error caused by socket aborts.
* **Remote Shutter Trigger**: Added remote capture trigger for Remote Viewfinder.
* **Build System Optimization**: Packaged classes for `d8` via jar to prevent Windows cmdline length overflows.

### Version 1.3.1
* **Download Traffic Isolation**: Pauses all background TCP keepalive pings during active photo/video downloads, eliminating TCP socket congestion on the camera's embedded web server and preventing unexpected "Network disconnected" errors during multi-file batch transfers.
* **Batch Transfer State Management**: Added global atomic download lock spanning multi-photo batch queues with 400ms buffer flush delays between downloads.
* **Lightweight Periodic Heartbeat**: Optimized idle keepalive to a single lightweight Mode Control ping every 8 seconds alongside UDP SSDP beacons.

### Version 1.3.0
* **Remote Viewfinder Mode Control**: Implemented port 7788 TCP/UDP Mode Control handshake (`/mode/control`), port 7679 stream touch (`/livestream.avi`), and dual-port reciprocal callback server (ports 7788 & 7792) so physical camera LCD displays "Connected" and resets the auto-off timer.
* **MobileLink Approval Auto-Retry**: Added 5-stage polling retry with delay to automatically detect and load photos as soon as the user approves the connection on the camera LCD.
* **Proactive Discovery Probes**: Sends Mode Control handshakes immediately during initial discovery scans.

### Version 1.2.0
* **Multi-level BFS directory traversal**: Recursively discovers sub-folders (`DCIM`, `100_PHOTO`) on SD card storage.
* **DIDL-Lite XML parser overhaul**: Resilient regex extraction for escaped `<Result>` tags and fallback photo titles.
* **Remote Viewfinder Keepalive**: Dedicated RVF handshake endpoints (`/smp_2`, `/smp_3`, `/smp_4`), RTSP `OPTIONS` ping (port 554), and TCP control pings (ports 50000–50002).
* **Dual Scheme Signing**: Signed with both v1 (JAR) and v2/v3 (APK Signature Scheme) for broad Android compatibility.

### Version 1.1.0
* Apple iOS Dark Mode visual redesign with status badges and action cards.
* Replaced copyrighted Samsung assets with a custom modern camera lens vector icon.
* Direct IP dialog for connecting across custom subnets.

### Version 1.0.0
* Initial release with SSDP camera discovery, UPnP content browsing, and background photo downloads.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
Samsung and NX1000 are trademarks of Samsung Electronics Co., Ltd. This project is an independent community development and is not affiliated with or endorsed by Samsung.
