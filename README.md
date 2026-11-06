# Hazora Mobile Camera

An Android phone camera that provides an MJPEG stream and JPEG snapshots for the Hazora dashboard.

## Run the camera

1. Open this repository in Android Studio and run the `app` configuration on an Android phone (Android 8.0 or newer).
2. Connect the phone and the dashboard computer to the same Wi-Fi network.
3. Grant camera permission and tap **Start camera**. Keep the app open while streaming; leaving the app stops the camera and its server.
4. Enter the phone's displayed IPv4 address in the dashboard's camera IP field and connect.

The app serves the MJPEG stream at `http://<phone-ip>:81/stream` and JPEG snapshots at `http://<phone-ip>/capture`. These ports and paths match the dashboard's existing camera and hazard-detection clients. `/health` reports whether a frame is available.

The camera server is started only after the user taps **Start camera**, binds to the phone's active Wi-Fi IPv4 address, and stops when the app leaves the foreground. Camera frames are served over unencrypted HTTP to devices on that Wi-Fi network. Use a trusted network and stop streaming when finished.

An HTTPS-hosted dashboard may be prevented by the browser from loading the camera's local HTTP endpoints. For local development, open the dashboard on the same network and grant any browser local-network access prompt.

## Build

The project uses Kotlin, Android Gradle Plugin 8.9.1, Gradle 8.13, and Android SDK 36.

Run the unit tests with `gradlew.bat testDebugUnitTest` on Windows or `./gradlew testDebugUnitTest` on macOS/Linux.
