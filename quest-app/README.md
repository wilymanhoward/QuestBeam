# Meta Quest 3 Native Casting App (Horizon OS)

A native Android application built for **Meta Quest 3** (and Quest 2/Pro) running on **Meta Horizon OS** (v68+).
- **No Unity**: Pure native Android APK using Kotlin, Jetpack Compose, Android `MediaProjection`, and WebRTC.
- **Horizon OS Spatial 2D Design**: Styled following Meta Horizon OS design guidelines with frosted glassmorphism, 1024x640 window layout, and 60dp accessible pill buttons for Touch Plus controllers and hand-tracking pinch gestures.
- **Intelligent Network Routing**:
  - **Local LAN**: Automatically streams directly P2P over high-speed Wi-Fi (UDP) when the Quest and laptop share the same network (<40ms latency, 0 internet bandwidth).
  - **Cloud Relay**: Automatically routes through WebRTC TURN/relay when devices are on different networks.
- **Persistent Background Casting**: Runs via an Android Foreground Service so streaming continues seamlessly even when you leave the panel and launch full 6DoF VR games (Beat Saber, Superhot, etc.) or Passthrough MR experiences.

---

## Project Structure
```
quest-app/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml   # Horizon OS metadata, VR intent category & panel sizing
│   │   ├── java/com/metaquest/cast/
│   │   │   ├── MainActivity.kt        # Horizon OS 2D Panel Activity
│   │   │   ├── service/
│   │   │   │   └── ScreenCaptureService.kt # Foreground service holding MediaProjection
│   │   │   ├── webrtc/
│   │   │   │   ├── WebRTCManager.kt        # Snapdragon XR2 Gen 2 hardware H.264 engine
│   │   │   │   └── MediaProjectionCapturer.kt # Frame capturer pipe
│   │   │   ├── network/
│   │   │   │   ├── NetworkDetector.kt     # Wi-Fi IP and subnet auto-discovery
│   │   │   │   └── SignalingClient.kt     # WebSocket signaling client
│   │   │   └── ui/                        # Jetpack Compose Horizon OS Design System
│   │   └── res/                           # Horizon OS styles, drawables & strings
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

---

## How to Build the APK

### Option 1: Using Android Studio (Recommended)
1. Open **Android Studio** on your computer.
2. Select **Open** and choose the `quest-app` directory:
   `d:\Project\Meta Quest App Project\quest-app`
3. Wait for Gradle sync to complete.
4. Go to **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. The generated APK will be located at:
   `quest-app/app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Using Command Line (Gradle)
If you have JDK 17+ and Android SDK installed:
```bash
cd quest-app
./gradlew assembleDebug
```

---

## How to Install on Meta Quest 3

### 1. Enable Developer Mode on Quest 3
1. Put on your Quest 3 or open the **Meta Horizon Mobile App** on your phone.
2. Navigate to **Devices > Headset Settings > Developer Mode** and toggle **Developer Mode** ON.

### 2. Sideload the APK (Choose any method below)

#### Method A: Meta Quest Developer Hub (MQDH) (Easiest)
1. Open **Meta Quest Developer Hub** on your computer.
2. Connect your Quest 3 via USB-C (or Wi-Fi ADB).
3. Drag and drop `app-debug.apk` directly into the MQDH **Apps** tab.

#### Method B: SideQuest
1. Open **SideQuest** (Desktop app or Web installer).
2. Click the **"Install APK from folder"** icon in the top navigation bar.
3. Select `app-debug.apk`.

#### Method C: ADB Command Line
Connect your Quest 3 via USB-C cable and run:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## How to Use & Cast

1. **Start Signaling & Web Receiver**:
   On your laptop, run:
   ```bash
   # Terminal 1: Signaling Server
   cd signaling-server
   npm start

   # Terminal 2: Web Receiver
   cd web-receiver
   npm start
   ```
   Open `http://localhost:3000` in your laptop browser. Note the 6-character room code (e.g., `Q3-7429`).

2. **Launch on Meta Quest 3**:
   - Put on your Quest 3.
   - Open the **App Library** and switch the filter in the top right from "All" to **"Unknown Sources"**.
   - Select **Quest Cast**.
   - The native Horizon OS panel will float in front of you.

3. **Pair & Start Casting**:
   - Enter the room code shown on your laptop (or tap **Auto-Detect** if on the same Wi-Fi).
   - Tap **"Start Screen Sharing"**.
   - Grant the native Meta Horizon OS screen capture permission dialog.
   - Your Quest 3 display is now mirroring in real-time on your laptop!
   - You can minimize or close the panel and jump into any VR game; streaming continues in the background.
