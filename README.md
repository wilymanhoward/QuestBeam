# ⚡ QuestBeam

<div align="center">

![Platform](https://img.shields.io/badge/Platform-Meta%20Horizon%20OS%20v68+-0082FB?logo=oculus&logoColor=white)
![Headset](https://img.shields.io/badge/Hardware-Meta%20Quest%203%20%7C%20Quest%202%20%7C%20Pro-555555)
![Architecture](https://img.shields.io/badge/Architecture-100%25%20Native%20Kotlin%20(No%20Unity)-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![Streaming](https://img.shields.io/badge/Streaming-WebRTC%20Hardware%20Accelerated-00C49F)
![Website](https://img.shields.io/badge/Live%20Receiver-questbeam.web.app-FFCA28?logo=firebase&logoColor=black)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

**High-performance, ultra-low latency wireless screen sharing from Meta Quest 3 directly to any laptop web browser.**

🌐 **Live Web Receiver:** [https://questbeam.web.app](https://questbeam.web.app)

[Live Demo](https://questbeam.web.app) • [Features](#-key-features) • [Architecture](#-system-architecture) • [Components](#-repository-structure) • [Quick Start](#-quick-start) • [Performance](#-performance--latency) • [License](#-license)

</div>

---

## 🌟 Overview

**QuestBeam** is an open-source wireless screen-casting ecosystem designed specifically for Meta Horizon OS. Unlike conventional solutions that rely on bulky Unity runtimes or cloud-tethered casting with noticeable lag, QuestBeam is built **100% natively in Kotlin with Jetpack Compose**. 

It runs as an authentic Horizon OS spatial 2D floating panel (just like the native WhatsApp and Instagram apps), utilizing the Snapdragon XR2 Gen 2 hardware video encoder and direct peer-to-peer WebRTC connections to achieve sub-25ms streaming to any web browser.

---

## 🚀 Key Features

- **100% Native Horizon OS (No Unity)**: Launches in under 1 second as a lightweight 2D floating spatial window without entering the 3D VR loading interstitial.
- **Background Foreground Service**: Casting continues uninterrupted when you minimize the window, browse system menus, or play immersive 6DoF VR/MR games (Beat Saber, Supernatural, etc.).
- **Smart Network Topology Auto-Detection**:
  - **Local LAN (Direct P2P)**: Automatically detects when the headset and laptop share the same Wi-Fi subnet and routes video directly peer-to-peer with zero internet data usage and ultra-low ping (<20ms).
  - **Cloud WebRTC Relay (TURN)**: Transparently routes through WebRTC relay when devices are on separate networks (e.g., cellular hotspot or remote viewing).
- **Subnet Auto-Discovery**: QuestBeam can automatically scan your local Wi-Fi subnet from inside the headset to discover the laptop receiver without manually typing IP addresses.
- **Hardware-Accelerated Encoding**: Direct GPU texture-to-encoder pipeline using the Snapdragon XR2 hardware encoder.
- **Modern Web Receiver**: Sleek Horizon OS dark glassmorphism interface featuring:
  - Real-time performance HUD (Live FPS, Bitrate Mbps, Round-Trip Latency, Resolution, Network Mode).
  - High-res snapshot tool (instant PNG screen captures).
  - Built-in stream recorder (records `.webm` / `.mp4` video locally in browser).
  - Fullscreen toggle and pairing QR code.

---

## 📐 System Architecture

```mermaid
flowchart TD
    subgraph Quest["Meta Quest 3 (Horizon OS)"]
        A[Compositor Display 0] -->|MediaProjection| B[MediaProjectionCapturer]
        B -->|SurfaceTextureHelper| C[Snapdragon XR2 Hardware H.264 Encoder]
        C -->|WebRTC RTP Track| D[WebRTC Engine]
        UI[Jetpack Compose Spatial UI] -->|Foreground Service| D
    end

    subgraph Signaling["Signaling Server (Node.js)"]
        S1[WebSocket Hub]
        S2[Network Topology Evaluator]
        S1 <--> S2
    end

    subgraph Browser["Laptop Web Receiver"]
        W1[WebRTC PeerConnection]
        W2[HTML5 Video Element]
        W3[Live Performance HUD]
        W1 -->|Low-Latency Video Sink| W2
        W1 -->|WebRTC Stats API| W3
    end

    D <-->|SDP / ICE Negotiation| S1
    S1 <-->|SDP / ICE Negotiation| W1

    D ===|Local Wi-Fi P2P LAN (<20ms)| W1
    D -.-|Cloud TURN Relay Fallback| W1
```

---

## 📁 Repository Structure

```text
QuestBeam/
├── quest-app/              # Native Android / Horizon OS Application
│   ├── app/                # Kotlin source (Jetpack Compose UI, WebRTC Engine)
│   ├── gradle/             # Gradle wrapper configuration
│   └── build.gradle.kts    # Build script with io.getstream:stream-webrtc-android
│
├── web-receiver/           # Browser Companion Viewer
│   ├── src/                # HTML5, Vanilla CSS glassmorphic design, and WebRTC client
│   └── package.json        # Static server script
│
└── signaling-server/       # WebSocket Signaling Coordinator
    ├── server.js           # Room pairing & subnet proximity detection
    ├── config.js           # STUN / TURN server configuration
    └── package.json        # Server dependencies (Express, ws, cors)
```

---

## ⚡ Quick Start

### Prerequisites
- **Meta Quest 3, Quest 2, or Quest Pro** with Developer Mode enabled.
- **Laptop / PC** with [Node.js](https://nodejs.org/) (v18+) and [Android Studio](https://developer.android.com/studio) or ADB.

---

### Step 1: Start the Laptop Services

1. **Start the Signaling Server**:
   ```bash
   cd signaling-server
   npm install
   npm start
   ```
   *The server will display its local network IP (e.g. `192.168.0.56:8080`).*

2. **Launch the Web Receiver**:
   ```bash
   cd web-receiver
   npm install
   npm start
   ```
   Open **`http://localhost:3000`** in Google Chrome, Edge, or Brave. You will see the QuestBeam interface displaying a room code (e.g. `Q3-CAST`).

---

### Step 2: Build & Install the Quest 3 App

#### Option A: Using ADB (Command Line)
```bash
cd quest-app
./gradlew assembleDebug
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

#### Option B: Using Android Studio
1. Open the `quest-app/` folder in Android Studio.
2. Select your connected Meta Quest device in the device dropdown.
3. Click **Run ▶** (or build APK via **Build > Build Bundle(s) / APK(s) > Build APK(s)** and sideload via **SideQuest** or **Meta Quest Developer Hub**).

---

### Step 3: Stream Your Screen

1. Put on your Meta Quest 3 headset.
2. Navigate to **App Library > Filter (Top Right) > Unknown Sources > QuestBeam**.
3. Confirm the room code matches your laptop receiver (default is `Q3-CAST`, or click **Auto-Detect**).
4. Click **Start Screen Sharing**.
5. When the Horizon OS prompt asks *"Start recording or casting with QuestBeam?"*, tap **Start now**.
6. **Done!** Your Quest 3 view streams live to your laptop with real-time FPS and latency readouts.

---

## 📊 Performance & Latency

| Parameter | Local Wi-Fi 6 (LAN Direct) | Cloud Relay (TURN) |
|---|---|---|
| **Latency** | 15 – 25 ms | 60 – 110 ms |
| **Framerate** | 60 FPS | 60 FPS / 30 FPS |
| **Supported Resolutions** | 1080p, 1440p, 720p | 1080p, 720p |
| **Internet Data Usage** | 0 MB (Direct P2P) | ~8 – 15 Mbps |
| **Hardware Overhead** | <3% CPU (XR2 Hardware Encoded) | <3% CPU |

---

## 🛠️ Tech Stack

- **Headset App**: Kotlin, Jetpack Compose, Android `MediaProjection`, Meta Horizon OS 2D Intent (`com.oculus.intent.category.2D`), `stream-webrtc-android`, OkHttp, Gson, Kotlin Coroutines.
- **Web Receiver**: HTML5, Vanilla CSS3 (Custom Glassmorphism Design System), WebRTC API, Canvas QR Generator, MediaRecorder API.
- **Signaling**: Node.js, WebSockets (`ws`), Express, Subnet Proximity Evaluator.

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.
