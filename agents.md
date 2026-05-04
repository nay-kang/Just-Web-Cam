# JustWebCam - Project Overview

**JustWebCam** is an Android application designed to stream real-time camera video over HTTP using the MJPEG format. It is built to run as a robust foreground service, ensuring continuous operation even when the app is in the background.

## Key Features
- **Low-Latency Streaming**: Efficient MJPEG delivery via a custom HTTP server.
- **Smart Overlays**: Real-time timestamp and battery level information rendered directly on the video frames.
- **Auto-Exposure Logic**: Intelligent adjustment of camera exposure settings to handle varying light conditions (e.g., "Deep Dark" mode).
- **Web-Based Monitoring**: A built-in web player (`stream_player.html`) for easy viewing from any browser on the network.
- **Service-Oriented Architecture**: Decoupled camera handling and streaming logic.

## Project Architecture

### Core Components

| Component | File | Purpose |
|-----------|------|---------|
| **MainActivity** | `MainActivity.kt` | Manages the UI, permissions, and lifecycle of the background service. |
| **CameraStreamService** | `CameraStreamService.kt` | The heart of the app. Handles Camera2 lifecycle, image capture, frame processing (overlays), and exposure control. |
| **StreamServer** | `StreamServer.kt` | A lightweight Kotlin Coroutine-based HTTP server that manages client connections and MJPEG streaming. |
| **Web Interface** | `stream_player.html` | The client-side player served by the app to view the stream. |

### Key Technologies
- **Camera2 API**: For granular control over camera hardware.
- **Kotlin Coroutines**: Powering the asynchronous `StreamServer`.
- **Jetpack Compose / Material3**: Modern UI stack for the management app.
- **Foreground Services**: Ensuring high-priority execution for reliable streaming.

---
*Last Updated: May 2026*
