# JustWebCam - Project Overview

**JustWebCam** is an Android application designed to stream real-time camera video over the network. It supports both MJPEG (HTTP) and RTSP streaming protocols, running as a robust foreground service for continuous operation.

## Key Features
- **Multi-Protocol Streaming**: Support for MJPEG (HTTP) and RTSP protocols with audio
- **Low-Latency Delivery**: Efficient frame processing with buffer pooling and YUV format handling
- **Smart Overlays**: Real-time timestamp and battery level information rendered directly on video frames
- **Auto-Exposure Logic**: Camera2 API with night mode and adaptive FPS (5-30 FPS)
- **Web-Based Monitoring**: Built-in web player (`stream_player.html`) for browser-based viewing
- **Protocol Switching**: Dynamic protocol change during runtime without restart
- **Auto-Start**: Optional automatic streaming on app launch

## Project Architecture

### Core Components

| Component | File | Purpose |
|-----------|------|---------|
| **MainActivity** | `MainActivity.kt` | Manages UI, permissions, protocol selection, and service lifecycle |
| **CameraStreamService** | `CameraStreamService.kt` | Foreground service handling Camera2 API, frame capture, and overlay rendering |
| **StreamService** | `StreamService.kt` | Abstract interface defining streaming contract |
| **StreamServiceFactory** | `StreamService.kt` | Factory for creating protocol-specific stream services |
| **MjpegStreamService** | `MjpegStreamService.kt` | HTTP-based MJPEG server with web interface |
| **RtspStreamService** | `RtspStreamService.kt` | RTSP server with H.264 video and AAC audio encoding |
| **StreamProtocol** | `StreamService.kt` | Enum defining supported protocols (MJPEG, RTSP) |

### Protocol Comparison

| Protocol | Port | Features | Use Case |
|----------|------|----------|----------|
| **MJPEG** | 8080 | HTTP-based, web viewer, video only | Browser viewing, simple integration |
| **RTSP** | 1935 | H.264/AAC, video + audio | Media players, higher quality |

### Key Technologies
- **Camera2 API**: Granular control over camera hardware with night mode
- **Kotlin Coroutines**: Powering the asynchronous `MjpegStreamService`
- **MediaCodec**: Hardware-accelerated H.264 video and AAC audio encoding
- **RTSP Server Library**: `com.pedro.rtspserver` for RTSP protocol handling
- **Foreground Services**: High-priority execution for reliable streaming

### Frame Processing Pipeline
1. Camera captures YUV_420_888 frames via `ImageReader`
2. Frames converted to NV21 format for processing
3. Optional overlay (timestamp + battery) rendered on Y plane
4. MJPEG: Frames compressed to JPEG and queued for HTTP delivery
5. RTSP: Frames converted to I420 and encoded with MediaCodec

---
*Last Updated: May 2026*