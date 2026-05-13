# Just Web Cam 📱🎥

**Turn your Android phone into a powerful wireless webcam!**

Effortlessly transform your device into a network-connected IP camera. Stream live video and audio directly to any device on your Wi-Fi network using standard protocols.

---

## 🚀 Quick Start

1. **Connect:** Ensure your phone and the viewing device (PC, tablet, etc.) are on the same Wi-Fi network.
2. **Launch:** Open **Just Web Cam** and grant the necessary permissions (Camera & Audio).
3. **Stream:** Toggle the switch to start the streaming service.
4. **Watch:** Use the addresses displayed on the screen to view your live feed:
   - **Browser (MJPEG):** Open `http://<your-ip>:8080` (Instant viewing, no setup needed)
   - **Media Player (RTSP):** Open `rtsp://<your-ip>:1935` (Supports Video + Audio, use with VLC, OBS, etc.)

---

## ✨ Key Features

- **Dual Protocol Support:**
  - **MJPEG (HTTP):** Simple, no-plugin browser viewing. Great for quick monitoring.
  - **RTSP:** High-quality H.264 video with AAC audio support. Ideal for professional software.
- **Smart Overlays:** Real-time timestamp and battery level information rendered directly on the video frames.
- **Low-Latency Streaming:** Optimized frame processing pipeline for minimal delay.
- **Auto-Exposure & Night Mode:** Leverages the Camera2 API for adaptive FPS and clear visibility in low-light conditions.
- **Background Reliability:** Runs as a foreground service to ensure uninterrupted streaming when the app is minimized.
- **Smart Camera Management:** Automatically releases the camera hardware when no clients are requesting a stream, helping to prevent overheating and extend battery life.
- **Minimalist & Lightweight:** Focused purely on reliable streaming without unnecessary bloat.

---

## 📊 Protocol Comparison

| Feature | MJPEG (HTTP) | RTSP |
| :--- | :--- | :--- |
| **Video Format** | Motion JPEG | H.264 |
| **Audio Support** | ❌ No | ✅ Yes (AAC) |
| **Port** | 8080 | 1935 |
| **Best For** | Browsers, simple integration | VLC, OBS, Security Software |
| **Approx. Latency** | ~200ms | ~700ms |

---

## 📉 Latency Test Results
*Tested on Snapdragon 865 (Xiaomi 10) with local Wi-Fi:*
- **Local Preview:** ~120ms
- **MJPEG Stream:** ~200ms
- **RTSP Stream:** ~700ms

---

## 🔋 Battery Optimization
For uninterrupted background streaming, especially on newer Android versions, please configure your device's battery settings to **"Don't Optimize"** or **"Unrestricted"**.

Refer to [dontkillmyapp.com](https://dontkillmyapp.com/) for device-specific instructions.

---

## ⚠️ Disclaimer
**Important:** This is an early access release and is currently in development. It is not yet considered fully stable, and features may change based on user feedback.

## 🤝 Feedback & Support
Found a bug? Have a suggestion?
Report it on our [GitHub Issue Tracker](https://github.com/nay-kang/Just-Web-Cam/issues).

---
*Thank you for helping us improve Just Web Cam!*
