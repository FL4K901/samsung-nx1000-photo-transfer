# Samsung NX1000 Wireless Photo Transfer (NX Cam Link)

[![Release](https://img.shields.io/badge/Release-v1.3.1-blue.svg)](release/NX-MobileLink-v1.3.1.apk)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2021--34)-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)
[![APK Size](https://img.shields.io/badge/APK%20Size-80%20KB-success.svg)](release/NX-MobileLink-v1.3.1.apk)

Modern, open-source Android application for wireless photo and video transfer from **Samsung NX1000** (and compatible Samsung NX series cameras) to modern Android smartphones running **Android 7.0 through Android 14+**.

---

## 📷 Overview & Background

Samsung discontinued official app support for its Smart Camera ecosystem years ago. The legacy **Samsung MobileLink** and **Samsung Remote Viewfinder** applications on the Google Play Store are incompatible with modern Android versions due to:
* Deprecated network APIs (lack of modern `NetworkCallback` and process socket binding).
* Strict cleartext HTTP restrictions in Android 9+.
* Changes in Android scoped storage permissions (Android 13+ `READ_MEDIA_IMAGES`).
* Aggressive Android Wi-Fi handoff (disconnecting from camera APs that have no internet access).

This project is a clean-room, reverse-engineered native Android solution that completely restores fast, stable, and seamless wireless photo transfer.

---

## ✨ Features

- **Apple-Inspired Dark Mode UI**: Clean, modern dark aesthetic featuring dynamic status capsules, frosted glass cards, and a custom copyright-free camera lens icon.
- **Deep Recursive Folder Browsing (BFS)**: Traverses multi-level SD card storage directories (`0` ➔ `Storage` ➔ `DCIM` ➔ `100_PHOTO`) so no photos are ever missed.
- **Dual Camera Mode Support**:
  - **MobileLink Mode**: The standard photo sharing mode on Samsung NX cameras with automated retry polling while approving on the camera LCD.
  - **Remote Viewfinder Mode**: Full Mode Control daemon integration (port 7788/7679) with reciprocal event callback server and live stream activation, acknowledging connection on the physical camera screen and suppressing the auto-power-off timer.
- **Anti-Timeout Keepalive Engine**: Periodic SSDP heartbeat, Mode Control registration (`NTS: alive`), raw HTTP description pings, and UPnP event subscriptions every 2.5 seconds to keep the camera's Wi-Fi radio from shutting off.
- **Batch & Single Downloads**: Select individual photos or "Select All" with one tap, saving directly to the Android MediaStore / Gallery with instant thumbnail indexing.
- **Ultra-Lightweight & Native**: Zero bloated third-party dependencies, built entirely using native Android APIs. The compiled, signed APK is only **~80 KB**!

---

## 📲 Download & Installation

You can download the pre-built, signed APK directly from this repository:

👉 **[Download NX-MobileLink-v1.3.1.apk](release/NX-MobileLink-v1.3.1.apk)**

1. Transfer or download the APK to your Android device.
2. Open the file and allow "Install from Unknown Sources" if prompted.
3. Open **NX Cam Link**.

---

## 🔌 How to Connect

### Option 1: MobileLink Mode (Recommended)
1. On your Samsung NX camera, turn the mode dial to **Wi-Fi**.
2. Select **MobileLink** from the camera screen.
3. Choose **"Select files on smartphone"** or **"All files"**.
4. **Important**: When the camera displays *"Allow connection from smartphone?"*, press **"Yes" (כן)** on the camera.
5. On your phone, connect to the camera's Wi-Fi network (usually named `Samsung_NX...` or similar).
6. Open **NX Cam Link** and tap **"סרוק וחבר מצלמה" (Scan & Connect)**.
7. Your photos and videos will load immediately in a responsive grid. Select the photos you want and tap **"הורד תמונות נבחרות" (Download Selected)**.

### Option 2: Remote Viewfinder Mode
1. Turn the mode dial to **Wi-Fi** and select **Remote Viewfinder**.
2. Connect your phone to the camera's Wi-Fi.
3. Open **NX Cam Link** and tap **"סרוק וחבר מצלמה"**.
4. The app sends the Samsung Mode Control (`/mode/control`) handshake on port 7788 and activates the live stream (`/livestream.avi` on port 7679), while running a reciprocal callback server on port 7788/7792. The camera LCD screen immediately acknowledges the connection and cancels the standby power-off timer.

---

## 🛠️ Building from Source

### Method A: One-Click PowerShell Build (Fastest, No Gradle Needed)
Requires Android SDK build-tools and JDK 17+:
```powershell
powershell -ExecutionPolicy Bypass -File build_apk.ps1
```
The output APK will be generated, zip-aligned, and signed with both v1 and v2 signature schemes in `release/NX-MobileLink-v1.3.0.apk`.

### Method B: Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `samsung-nx1000-photo-transfer` directory.
3. Allow Gradle to sync.
4. Click **Run** or **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

---

## 🔬 Protocol Reverse-Engineering Details

- **Device Discovery**: The app sends UDP `M-SEARCH * HTTP/1.1` to `239.255.255.250:1900` searching for `urn:schemas-upnp-org:device:MediaServer:1` and probes candidate gateway ports (52235, 7676, 7679, 7788, 4004, 8080).
- **Samsung Mode Control Handshake (Port 7788 & 7679)**:
  ```http
  GET /mode/control HTTP/1.0
  HOST: 192.168.107.1:7788
  User-Agent: SEC_MODE_<MAC>
  Access-Method: manual
  NTS: alive
  Content-Length: 0
  HOST-Mac: <MAC>
  HOST-Address: <PHONE_IP>
  HOST-port: 7788
  Connection: close
  ```
- **Reciprocal Callback Server**: Runs a local server on port 7788 (and port 7792) returning `HTTP/1.1 200 OK` to acknowledge `/eventCallback` and `/mode/control` incoming queries from the camera.
- **Client Identity**:
  - MobileLink: `User-Agent: SEC_DSC_<PHONE_HEX_MAC>`
  - Remote Viewfinder: `User-Agent: SEC_RVF_<PHONE_HEX_MAC>`
- **Browsing Service**: Communicates with the UPnP `ContentDirectory:1` service using SOAP `Browse` actions with chunk sizes of 20–25 items to prevent buffer overruns on the camera's embedded VxWorks HTTP server.
- **Wi-Fi Binding**: Uses `ConnectivityManager.bindProcessToNetwork` (Android 6.0+) so that Android does not route requests through cellular data when connected to the camera AP.

---

## 📋 Changelog

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
