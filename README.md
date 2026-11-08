# Samsung NX1000 Wireless Photo Transfer (NX Cam Link)

[![Release](https://img.shields.io/badge/Release-v1.5.0-blue.svg)](release/NX-MobileLink-v1.5.0.apk)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2021--34)-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)
[![Attribution](https://img.shields.io/badge/Legal-Attribution-orange.svg)](ATTRIBUTION.md)
[![Security](https://img.shields.io/badge/Security-OWASP%20Hardened-brightgreen.svg)](README.md#security--hardening)

Modern, open-source Android application for wireless photo/video transfer and live remote viewfinder from **Samsung NX1000** (and compatible Samsung NX series cameras) to modern Android smartphones running **Android 7.0 through Android 14+**.

---

## 📷 Overview & Background

Samsung officially discontinued support for its Smart Camera ecosystem years ago. Legacy apps on the Google Play Store (**Samsung MobileLink** and **Samsung Remote Viewfinder**) fail on modern Android devices due to:
* Deprecated network APIs (lack of modern `NetworkCallback` and process socket binding).
* Strict cleartext HTTP restrictions introduced in Android 9+.
* Changes in Android scoped storage permissions (Android 13+ `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`).
* Aggressive Android Wi-Fi handoff (disconnecting from camera APs that have no internet access).
* VxWorks RTOS socket exhaustion on the camera due to modern Android HTTP/1.1 connection pooling.

This project is a clean-room, reverse-engineered native Android solution that completely restores fast, stable, and seamless wireless photo transfer and live remote viewfinder control.

---

## ✨ Features

- **Apple-Inspired Dark Mode Home Screen**: Clean, modern dark dashboard featuring an interactive Wi-Fi status capsule and modular cards for **MobileLink** and **Remote Viewfinder**.
- **100% Modular Protocol Architecture**: Completely separates MobileLink and Remote Viewfinder into independent clients and activities, preventing cross-protocol interference or playback engine deadlocks.
- **Pure UPnP/DLNA MobileLink Engine**:
  - Speaks authentic UPnP `ContentDirectory:1` with `SEC_DSC_<MAC>;<Name>;<IP>` mutual authentication identity.
  - CyberGarage dual-port SSDP discovery on multicast port 1901 (MobileLink) and 1900 (Remote Viewfinder) with unicast response socket on port 8009 and HTTP EventSub server on port 8059 (`/evetSub`).
  - Strict CyberGarage HTTP/1.0 root XML & SCPDURL parsing handshake that signals the camera to transition out of "Waiting for connection...".
  - Deep Recursive Folder Browsing (BFS) with pagination (100 root items, 20 child items per page) across multi-level SD card directories (`0` ➔ `Storage` ➔ `DCIM` ➔ `100_PHOTO`).
  - Direct automatic photo loading without artificial polling delays.
  - Zero-collision downloading with automated deduplication and modern MediaStore integration (`Pictures/NX1000`).
  - Global atomic download lock with silent keepalive suspension during downloads, preventing socket congestion and camera disconnects.
- **Dedicated Remote Viewfinder Engine**:
  - Clean UPnP RVF protocol reconstructed from official Samsung APK bytecode.
  - Mutual authentication via root XML & SCPDURL description fetch with `SEC_RVF_<MAC>` User-Agent.
  - Dynamic stream discovery via single SOAP `GetInfomation` returning `<GETIPRESULT>` (`StreamURL`, e.g. `http://<cam_ip>:8080/stream.jpg`) and `<AVAILSHOTS>`.
  - Continuous live MJPEG stream decoding directly from the camera's dynamic endpoint.
  - Eliminated fabricated non-existent background probes (ports 7788/7679) and 3s polling that previously caused camera socket exhaustion.
  - Remote shutter trigger via UPnP SOAP `Shot` with automated post-capture `Thumbnaildown` (`FileURL + "_SM"`) cycle to cleanly resume live streaming.
- **Ultra-Lightweight & Native**: Zero bloated third-party dependencies, built entirely using native Android APIs. The compiled, signed APK is only **~100 KB**!

---

## 🔒 Security & Hardening

This application underwent a comprehensive OWASP security review and incorporates full defensive hardening:

1. **Path Traversal Protection**: All downloaded file names are rigorously sanitized, stripping path traversal sequences (`..`, `/`, `\`), and enforced via canonical directory containment (`canonicalDest.getPath().startsWith(canonicalTarget.getPath())`).
2. **Secure XML Parsers (Anti-XXE)**: All XML document parsers explicitly disable `DOCTYPE` declarations, external general entities, external parameter entities, external DTD loading, and entity expansion (`disallow-doctype-decl = true`).
3. **DoS Prevention (Bounded Buffers & Threading)**:
   - MJPEG live stream reader enforces a 5MB maximum frame size ceiling, discarding malformed streams.
   - HTTP header reader caps line lengths at 8192 bytes against slowloris attacks.
   - Local keep-alive callback server employs a bounded 4-thread `ExecutorService` pool instead of unbounded thread creation.
4. **Data Privacy**: Backup extraction is explicitly disabled (`android:allowBackup="false"`).
5. **SSRF Mitigation**: DLNA resource URLs are verified against the camera's base host and private subnets, rejecting loopback and arbitrary redirections.
6. **SOAP Argument Sanitization**: All UPnP SOAP request parameters and XML attributes are strictly escaped against XML/SOAP injection.

---

## 📲 Download & Installation

You can download the pre-built, signed APK directly from this repository:

👉 **[Download NX-MobileLink-v1.5.0.apk](release/NX-MobileLink-v1.5.0.apk)**  
*(Also available as [NX-MobileLink.apk](release/NX-MobileLink.apk))*

1. Transfer or download the APK to your Android device.
2. Open the file and allow "Install from Unknown Sources" if prompted.
3. Open **NX Cam Link**.

---

## 🔌 How to Use

### 1. Home Screen
When you launch the app, you will see the **NX Cam Link** dashboard:
* **Wi-Fi Status Capsule**: Shows current Wi-Fi SSID and connection status dot (tap to open Wi-Fi settings).
* **Card 1: MobileLink** (Green icon) ➔ Tap to browse and download photos/videos from SD card.
* **Card 2: Remote Viewfinder** (Blue icon) ➔ Tap for live video stream and remote shutter control.

### 2. MobileLink Mode (Transfer Photos & Videos)
1. On your Samsung NX camera, turn the mode dial to **Wi-Fi**.
2. Select **MobileLink** ➔ choose **"Select files on smartphone"** or **"All files"**.
3. In the app, tap the **MobileLink** card and press **"Connect Camera"**.
4. The app binds to the camera's Wi-Fi network and automatically browses the SD card.
5. Your photos load in a fast 3-column grid. Tap any photo for preview or select multiple files to download directly to your Android Gallery (`Pictures/NX1000`).

### 3. Remote Viewfinder Mode (Live Preview & Shutter)
1. Turn the camera mode dial to **Wi-Fi** and select **Remote Viewfinder**.
2. Connect your phone's Wi-Fi to the camera network (e.g., `NX1000-...`).
3. In the app, tap the **Remote Viewfinder** card and press **"Connect Camera"**.
4. The camera displays "Connected to smartphone", live preview streams to your phone screen, and you can tap the circular shutter button to capture photos remotely!

---

## 🛠️ Building from Source

### Method A: One-Click PowerShell Build (Fastest, No Gradle Needed)
Requires Android SDK build-tools 34+ and JDK 17+:
```powershell
powershell -ExecutionPolicy Bypass -File build_apk.ps1
```
The output APK will be generated, zip-aligned, and signed with v1, v2, and v3 signature schemes in `release/NX-MobileLink-v1.5.0.apk`.

### Method B: Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `samsung-nx1000-photo-transfer` directory.
3. Allow Gradle to sync.
4. Click **Run** or **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

---

## 📋 Changelog

### Version 1.5.0
* **Official Samsung Protocol Reverse-Engineering & Full Mutual Authentication**:
  - Reconstructed exact CyberGarage UPnP SSDP protocol from `mobilelink-1-7-13.apk` and `remote-viewfinder-2-2-11-multi-android.apk`.
  - Added dual SSDP discovery listening on multicast port `1901` (`mobilelink`) and `1900` (`remote-viewfinder`) simultaneously.
  - Implemented unicast M-SEARCH response socket on port `8009` and UPnP GENA EventSub server on port `8059` (`/evetSub`).
  - Implemented authentic root description XML & SCPDURL `HTTP/1.0` `GET` mutual authentication with `SEC_DSC_<MAC>;<Name>;<IP>` (MobileLink) and `SEC_RVF_<MAC>` (Remote Viewfinder) User-Agents.
  - Reconstructed genuine Remote Viewfinder sequence: single SOAP `GetInfomation` returning `<GETIPRESULT>` (`StreamURL`, e.g. `http://<cam_ip>:8080/stream.jpg`), followed by direct live MJPEG stream decoding.
  - Completely eliminated non-existent ports 7788/7679 probes and 3-second polling loops that previously overloaded camera RTOS sockets.
  - Added post-capture `Thumbnaildown` protocol (`GET FileURL + "_SM"`) in `ViewfinderClient` to complete camera capture cycle.
  - Added ContentDirectory pagination (`RequestedCount = 100` for root `0`, `20` for child folders) and smart gateway/DHCP IP candidate resolution across all private subnets.

### Version 1.4.3
* **Comprehensive OWASP Security Hardening**:
  - Remediated all 8 vulnerabilities identified in security triage (XXE, Path Traversal, Unbounded Buffers, Slowloris, Thread Exhaustion, Insecure Backup, SOAP Injection, and DLNA SSRF).
  - Added filename sanitization and canonical directory confinement in `MobileLinkClient.java`.
  - Added secure `DocumentBuilderFactory` with DOCTYPE and external entity disallowal.
  - Added 5MB frame buffer limit in `ViewfinderClient.java` and 8KB header line limit in `HumbleHttpClient.java`.
  - Replaced unbounded threads in `LocalRvfServer` with a fixed thread pool of 4 workers.
  - Disabled `android:allowBackup`.
* **100% English Internationalization**:
  - Fully translated all remaining UI labels, buttons, dialogs, progress banners, and comments into English.
* **Rights Preservation & Legal Attributions**:
  - Added comprehensive [ATTRIBUTION.md](ATTRIBUTION.md) documenting trademarks, fair-use reverse-engineering rationale, and credits.
  - Added SPDX-License-Identifier and Copyright headers across all Java source files.
* **Streamlined Automatic DLNA Loading**:
  - Eliminated artificial camera approval polling loops; photos now query and render automatically upon connection.

### Version 1.4.2
* **Universal Process-Level Wi-Fi Binding (`NetworkManager`)**:
  - Solves the modern Android cellular data bypass bug where Android routes camera traffic to mobile data when connected to an AP with no internet.
  - Synchronously scans `ConnectivityManager.getAllNetworks()` and executes `cm.bindProcessToNetwork()` across all activities.
* **Multi-Source Camera IP Discovery**:
  - Automatically queries `LinkProperties.getRoutes()`, `DhcpInfo.gateway`, `DhcpInfo.serverAddress`, and derives subnet `.1` from the device's IP.
* **Socket-Gentle Port Probing**:
  - Fast 400ms TCP socket pre-checks on primary Samsung ports (**52235**, **7676**, **7679**, **7788**, **80**), protecting the camera's RTOS socket pool.

### Version 1.4.1
* **HumbleHttpClient Architecture (Socket Exhaustion Fix)**:
  - Reverse-engineered from Samsung's legacy APK (`HumbleHttpClient.java`). Replaces Android's `HttpURLConnection` with raw TCP sockets using strict `HTTP/1.0` headers and immediate socket closure.
  - Eliminates the VxWorks RTOS socket exhaustion bug that previously disconnected the Wi-Fi link after downloading ~3 photos.
* **Samsung UPnP SOAP Remote Viewfinder Protocol**:
  - Dynamic endpoint and stream discovery via UPnP SOAP `GetInfomation`.
  - Hardware shutter capture triggered via UPnP SOAP `Shot` / `ShotWithGPS` action.

### Version 1.4.0
* **Modular Dashboard Architecture**:
  - Introduced Apple Dark Mode Home Screen with separate interactive cards for **MobileLink** and **Remote Viewfinder**.
  - Complete protocol separation between `MobileLinkClient.java` and `ViewfinderClient.java`.

---

## ⚖️ Legal Notice & Attribution

- **Copyright**: (c) 2026 FL4K901. All rights reserved.
- **License**: Licensed under the [MIT License](LICENSE).
- **Trademarks**: **Samsung**, **Samsung NX**, **NX1000**, **MobileLink**, and **Remote Viewfinder** are trademarks of **Samsung Electronics Co., Ltd.** This project is an independent community development and is not affiliated with, sponsored, or endorsed by Samsung.
- **Third-Party Credits**: Special thanks to **Istvan Safar** ([SamsungCameraDownloader](https://github.com/IstvanSafar/SamsungCameraDownloader)) for open-source research and reference implementation of Samsung DLNA structures. See [ATTRIBUTION.md](ATTRIBUTION.md) for full details.
