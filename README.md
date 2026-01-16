# NexCam

A high-performance Android camera streaming tool built with CameraX and Ktor, supporting real-time image processing and MJPEG streaming over LAN.

## Key Features

*   **Live Streaming**: Built-in Ktor server for real-time MJPEG video streaming accessible via web browsers.
*   **Camera Controls**: Manual adjustment of Exposure Compensation (EV) and Zoom.
*   **Extension Modes**: Support for HDR and Night modes (hardware dependent).
*   **Image Overlays**: Real-time custom text watermarks and timestamps on the video stream.
*   **Performance Monitoring**: Live FPS display to monitor capture stability.
*   **Modern UI**: Built entirely with Jetpack Compose and Material 3, supporting immersive full-screen display.
*   **Eco Mode**: OLED-friendly black screen saver with anti-burn-in moving text.

## Tech Stack

*   **Language**: Kotlin
*   **UI Framework**: Jetpack Compose
*   **Camera API**: CameraX (Core, Camera2, Extensions)
*   **Networking**: Ktor (Netty)
*   **Concurrency**: Kotlin Coroutines & Flow

## Quick Start

1.  Open the project in **Android Studio** and install it on an Android device (API 26+).
2.  Grant the required Camera and Network permissions.
3.  (Optional) Tap the settings icon to adjust resolution, watermark, or enable HDR.
4.  Tap **Start Server**.
5.  Access the displayed URL (e.g., `http://192.168.x.x:8080/live`) in a browser on the same network.

## Notes

*   Availability of HDR/Night modes depends on the device manufacturer's CameraX Extension implementation.
*   MJPEG streaming can be CPU and bandwidth intensive at high resolutions; using a stable Wi-Fi connection is recommended.
