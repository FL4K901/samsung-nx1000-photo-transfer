# Samsung NX Wireless Camera Controller

[![Release](https://img.shields.io/badge/Release-v1.6.8-blue.svg)](release/NX-MobileLink-v1.6.8.apk)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

Native Android application providing wireless media transfer and live remote viewfinder control for Samsung NX series digital cameras.

The application restores wireless connectivity between modern Android smartphones and legacy Samsung smart cameras, replacing discontinued vendor applications and overcoming modern platform restrictions across Android 7.0 through Android 14.

---

## Overview

Legacy Samsung Smart Camera applications ceased functionality on modern Android releases due to fundamental platform shifts:
- Deprecation of legacy connection workflows and absence of socket process binding
- Platform level restrictions on cleartext HTTP traffic across local networks
- Android Scoped Storage model replacing legacy shared storage access
- Aggressive Wi-Fi validation services disconnecting networks lacking Internet gateways
- Thread and socket pool exhaustion on embedded camera runtimes caused by modern HTTP connection pooling

This project provides a clean, independent native Android client designed to communicate directly with the embedded camera services over local Wi-Fi.

---

## Architecture

The project is structured into modular layers designed for high reliability and zero cross-protocol interference:

### MobileLink Module
- Implements UPnP ContentDirectory services to traverse storage structures on the camera SD card
- Provides recursive directory indexing with paginated query batches
- Manages high speed file transfer with automatic deduplication
- Integrates directly with Android MediaStore to register captured media into the system gallery

### Remote Viewfinder Module
- Establishes a direct TCP transport for live video streaming
- Implements an integrated container demuxer and H.264 hardware decoding pipeline via Android MediaCodec
- Provides real-time camera orientation detection and responsive viewport rendering
- Executes hardware autofocus sequences and remote shutter release commands

### Network Management Layer
- Binds the application process to the active camera Wi-Fi interface using ConnectivityManager
- Prevents Android cellular data routing from hijacking local camera network packets
- Discovers camera gateway addresses dynamically across private network topologies

---

## Technical Engineering Highlights

### Zero Backpressure Frame Pipeline
Live video streaming from embedded hardware shares real-time resources with the camera internal display. If the mobile receiver stalls or falls behind rendering incoming frames, network buffers accumulate, triggering TCP backpressure that freezes the camera display pipeline.

The decoding engine implements a non-blocking frame dispatch system:
- Output frames from MediaCodec are evaluated against UI thread render readiness
- When the UI thread is processing a previous frame, newly decoded video buffers are released immediately without expensive color conversion
- Frame conversion utilizes static lookup tables and pre-allocated double buffers, eliminating heap allocations and garbage collection pauses during live streaming
- Network socket buffers remain cleared at wire speed, ensuring consistent transmission and preventing camera runtime stalls

### Embedded Runtime Socket Optimization
The camera embedded operating system maintains a strictly limited TCP socket pool. Standard modern HTTP clients keep persistent connections open, rapidly exhausting available handles on the camera.

A custom HTTP engine was developed to handle network transactions:
- Enforces single-transaction socket lifecycles with immediate teardown
- Sends explicitly formatted headers matching embedded server expectations
- Employs an active keepalive heartbeat during idle viewfinder sessions to keep camera services awake without resource exhaustion

### Command Serialization
Lens autofocus motors and physical mechanical shutters require finite hardware execution time. Concurrent SOAP requests sent while the camera hardware is moving can cause device lockups.

A dedicated reentrant locking mechanism serializes all camera control commands:
- Shutter requests trigger autofocus evaluation before releasing the physical shutter
- Lens focus locks feature automatic timeout release mechanisms to ensure the camera never remains locked in focus state
- Mutual exclusion guarantees single-threaded request execution at all times

---

## Security and Hardening

The application incorporates defensive controls to ensure secure execution:
- Path Traversal Defense: Inbound file names are strictly sanitized to strip directory traversal sequences, with canonical path verification against the designated storage root
- XML Parser Hardening: All XML parsers explicitly disable external entity resolution, external DTD loading, and DOCTYPE declarations to prevent XML External Entity injection
- SSRF and Subnet Containment: Media URLs received over UPnP are validated against trusted private subnet ranges, rejecting loopback, non-routable, or public addresses
- Sandbox Isolation: Application backup is explicitly disabled, and non-launcher activities are configured with exported false to block external application intent access

---

## Building from Source

### Prerequisites
- JDK 17 or higher
- Android SDK Build Tools 34 or higher

### Command Line Build
To compile, package, and sign the application:
```powershell
powershell -ExecutionPolicy Bypass -File build_apk.ps1
```
The output APK is generated, zip-aligned, and signed with v1, v2, and v3 signatures in `release/NX-MobileLink-v1.6.8.apk`.

### Android Studio
1. Open Android Studio.
2. Select Open and navigate to the project root directory.
3. Allow the project configuration to complete.
4. Select Build then Build APK.

---

## Installation

Pre-compiled signed application binaries are available in the release directory:
- [NX-MobileLink-v1.6.8.apk](release/NX-MobileLink-v1.6.8.apk)
- [NX-MobileLink.apk](release/NX-MobileLink.apk)

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for complete terms.
