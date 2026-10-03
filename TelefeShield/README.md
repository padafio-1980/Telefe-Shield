# Telefe Shield

A minimal Android TV app for NVIDIA Shield. On launch it:
1. Loads the official Telefe live page.
2. Extracts the current `data-player-url`.
3. Requests a fresh HLS URL from Telefe's `/vidya/tokenize` endpoint.
4. Plays the resulting HLS stream fullscreen with AndroidX Media3/ExoPlayer.

The Shield must have network access to the stream. If Telefe restricts the stream geographically, the app does not bypass that restriction; it simply uses the Shield's existing network/VPN connection.

## Cloud build
Push this folder to a GitHub repository. The included GitHub Actions workflow builds `app-debug.apk` and uploads it as the `Telefe-Shield-APK` artifact.
