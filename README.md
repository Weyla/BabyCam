# BabyCam

BabyCam is an open-source Android app that turns two phones on the same local
network into a private video and audio monitor. Either phone can stream or
receive, and the RTSP feed can also be consumed by compatible local software.

> [!WARNING]
> BabyCam was created by artificial intelligence. Although the source has been
> reviewed and tested, it may contain defects and must not be trusted as the
> only means of child supervision or surveillance. Always
> maintain appropriate direct adult supervision and use dedicated safety
> equipment where necessary. BabyCam is recommended only on trusted local
> networks; its RTSP, RTP, and talkback media are not encrypted.

## Features

- Video with audio or audio-only streaming between Android phones.
- 480p, 720p, and 1080p video modes with an optional low-latency mode.
- Background audio playback while the receiving phone is locked.
- Standby mode that starts capture when an authenticated receiver requests it
  and returns to standby after the last viewer disconnects.
- Automatic reconnection and an optional delayed connection-loss alarm with a
  selectable sound and adjustable volume.
- Local device discovery and QR pairing.
- Picture-in-picture and full-screen receiver playback.
- Streamer battery and charging information with low-battery warnings.
- Password-protected camera switching, torch, zoom, resolution controls, and
  push-to-talk.
- Configurable RTSP username, password, and port.
- Multiple simultaneous RTSP receivers.

## RTSP and Frigate

The default stream address is:

```text
rtsp://PHONE_IP:8554/live
```

The feed can be opened by another BabyCam receiver or by compatible RTSP
software. For example, it can be added to a local
[Frigate](https://frigate.video/) installation for recording and detection.
BabyCam itself does not record video. Recording, retention, and privacy are the
responsibility of the external software and its operator.

## Basic use

On the streaming phone, select **Stream**, choose video and audio or audio only,
then tap **Start stream**. Alternatively, configure a stream password and use
**Standby** so an authenticated receiver can wake the selected stream mode.

On the receiving phone, select **Receive**, choose a discovered device or scan
its QR code, enter the matching password when configured, and tap **Connect**.
Basic RTSP playback can be passwordless; Standby, battery information, remote
controls, resolution changes, and push-to-talk require the same password on
both phones.

Both devices must be reachable through private IPv4 addresses on the same Wi-Fi
or local network. Guest Wi-Fi networks may block communication between clients.

## Privacy and security

- There is no cloud service, account, analytics, or built-in recording.
- The built-in receiver accepts private IPv4 destinations only.
- Pairing QR codes never contain the stream password.
- Passwords are stored in app-private storage and excluded from Android backup.
- Media is not end-to-end encrypted. Anyone with access to the trusted network
  may be able to observe traffic, so do not expose the ports to the internet.
- Passwordless RTSP is provided for compatibility. Use a strong password of at
  least eight characters on shared networks.

## Requirements and build

BabyCam supports Android 8.0 (API 26) and newer. Building requires JDK 17 and
Android SDK 36.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
```

## License

BabyCam is licensed under the [GNU General Public License v3.0](LICENSE).
Copyright and license notices must be retained, and distributed modified
versions must comply with the GPLv3 source-availability requirements.
