# Hazora Mobile Camera

An Android phone camera that provides an MJPEG stream and JPEG snapshots for the Hazora dashboard.

## Download the app

Scan this QR code to open the [GitHub Releases page](https://github.com/haruto-web/Hazora-Mobile-Camera/releases). Download the APK attached to the latest pre-release. The QR code remains the same for future releases.

![QR code to download the Hazora Mobile Camera app from GitHub Releases](docs/download-qr.png)

## Run the camera

1. Open this repository in Android Studio and run the `app` configuration on an Android phone (Android 8.0 or newer).
2. Connect the phone running the app and the device running the Hazora dashboard to the same Wi-Fi network. The dashboard must be able to reach devices on that network.
3. Grant camera permission and tap **Start camera**. Keep the app open while streaming; leaving the app stops the camera and its server.
4. Copy the phone's displayed IPv4 address, enter only that IP in a dashboard Live Streams box, and connect.

The app serves the MJPEG stream at `http://<phone-ip>:8081/stream` and JPEG snapshots at `http://<phone-ip>:8080/capture`. The dashboard automatically tries the phone-camera endpoints and the standard ESP32-CAM endpoints. `/health` reports whether a frame is available.

The camera server is started only after the user taps **Start camera**, binds to the phone's active Wi-Fi IPv4 address, and stops when the app leaves the foreground. It uses ports above 1023 because Android does not allow ordinary apps to bind to privileged ports such as 80 and 81. Camera frames are served over unencrypted HTTP to devices on that Wi-Fi network. Use a trusted network and stop streaming when finished.

An HTTPS-hosted dashboard may be prevented by the browser from loading the camera's local HTTP endpoints. For local development, open the dashboard on the same network and grant any browser local-network access prompt.

## Build

The project uses Kotlin, Android Gradle Plugin 8.9.1, Gradle 8.13, and Android SDK 36.

Run the unit tests with `gradlew.bat testDebugUnitTest` on Windows or `./gradlew testDebugUnitTest` on macOS/Linux.
