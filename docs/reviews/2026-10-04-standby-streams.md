# Standby battery drain and stream disconnect review

Reviewed commit `26bfe6c`, version 1.2.1, against the history back to 1.0.
The reported problem is an unplugged streamer displaying **Standby** falling
from more than 90% to 9% in approximately a day. The last reliable version is
estimated to be three releases earlier; the exact installed versions and phone
models are unknown.

The strongest battery finding is a CPU wake lock held for the entire standby
session. The strongest recent connection regression is that a brief audio
buffering event now tears down the independent video session. Several other
shutdown paths amplify network interruptions. The behaviors below are confirmed
in code or isolated probes; their contribution on the actual phones has not been
measured. No Android device was connected. These findings describe the original
checkout before the corrections below; historical line references refer to
commit `26bfe6c`.

## Corrections implemented after the review

- Idle standby no longer acquires a CPU wake lock. Capture startup and active
  streaming acquire it, and every capture shutdown releases it, including when
  returning to standby.
- Brief audio buffering keeps the healthy video session and visible picture.
  Actual playback failure and the existing eight-second buffering watchdog still
  trigger recovery. Outage alarm timing is preserved.
- A standby-started stream waits ten seconds after the last playing viewer
  leaves. Rejoining cancels shutdown, and timeout execution rechecks current
  demand and stream state.
- RTSP output now queues whole frames with a 2 MiB, 96-frame, 750 ms budget.
  Congestion discards queued video and waits for an IDR; audio remains bounded
  and live. Temporary stalls and large keyframes do not immediately disconnect
  clients. A writer without progress for ten seconds is disconnected.
- Initial video startup failure retains healthy audio and removes unavailable
  video from SDP and discovery. Asynchronous failure before usable video
  configuration follows the same capability correction.
- Receiver Pause stops and clears both sessions. RTSP PAUSE decrements active
  viewer counts, and resumed/repeated PLAY cannot double-count a viewer.
- Network selection retains the current private LAN while it remains valid.
  Active listeners and discovery keep one port/credential configuration until
  capture stops. Actual address changes still rebuild listeners.
- The primary receiver selects supported audio first and falls back to video
  before RTSP SETUP when audio is absent or unsupported. Video-only cameras use
  the primary player in regular, fullscreen and picture-in-picture views.
- Both latency modes use TCP; low latency still changes buffer/encoder settings.
  RTSP diagnostics distinguish read/write failures, stalled writers, teardown,
  congestion, and recovery without logging credentials.

Regression tests cover standby lock ownership and reconnect grace, audio
buffering and video failure isolation, queue congestion and expiry, a real
310,000-byte fragmented keyframe with a blocked writer, active PLAY/PAUSE counts,
audio-only SDP fallback, stable LAN selection, configuration snapshots, and the
Media3 track-selection engine for audio/video and video-only sources.

Post-fix validation: `./gradlew testDebugUnitTest lintDebug assembleDebug --offline`
passed. All 59 unit tests passed with no skips. Lint reported no errors and the
existing Mockito dependency-update warning. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk`.

Device validation remains required: compare overnight standby battery use,
screen-off remote wake, and sustained audio/video playback on both actual phones.
Allowing the CPU to sleep means Android Doze or manufacturer power restrictions
can delay an incoming LAN request. Unit tests and a successful APK build cannot
measure that tradeoff.

## Findings, in priority order

### 1. Idle standby holds the stream CPU wake lock indefinitely

**Priority: high. Confidence: confirmed behavior; strongest explanation for
standby drain. Introduced: 1.0.2 (`d81d1a0`).**

[`RtspCameraService.ensureControlServer()`](../../app/src/main/java/com/babycam/RtspCameraService.java#L277)
acquires `com.babycam:stream`, a non-reference-counted `PARTIAL_WAKE_LOCK`, whenever
standby is enabled. It does so without an expiry and before checking whether the
control listener needs to be created. Holding the lock prevents normal CPU
suspend when honored by Android; it does not mean that a CPU core is executing
at full utilization.

[`stopStreaming()`](../../app/src/main/java/com/babycam/RtspCameraService.java#L482)
stops the camera, microphone, encoders, talkback and RTSP server, but explicitly
skips releasing the lock while standby remains enabled (line 508). Consequently
**the displayed Standby state means capture is off, but does not mean the phone
can sleep normally**. After a stream ends, the lock can also remain held while
the service waits for a LAN to return.

An isolated probe invokes `stopStreaming()` with standby enabled and a held
wake lock: capture is stopped and `running` becomes false, but `release()` is
never called. Initial standby acquisition is explicit in line 279.

Before 1.0.2, standby did not acquire this lock and stream shutdown released it
unconditionally. However, version 1.0.3—three release steps before 1.2.1—already
contains this policy. The version estimate therefore does not establish that
this particular policy was newly introduced since the last successful overnight
test.

**Recommended correction:** give idle standby an explicit power policy, release
the stream wake lock when capture stops, and acquire it for startup/active
capture. Test screen-off remote wake before shipping that change. Immediate LAN
reachability during deep sleep is a separate requirement: simply deleting the
lock cannot guarantee it. If continuous reachability requires keeping a given
phone awake, expose that battery tradeoff explicitly rather than describing it
as low-power standby. Android documents both the battery impact of wake locks
and the network restrictions of Doze:
[wake locks](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock),
[Doze](https://developer.android.com/training/monitoring-device-state/doze-standby).

### 2. Brief audio buffering disconnects healthy video

**Priority: high. Confidence: confirmed behavior; plausible explanation for more
visible interruptions. Introduced: 1.1.0 (`cbaf776`).**

[`ReceiverService.onPlaybackStateChanged()`](../../app/src/main/java/com/babycam/ReceiverService.java#L832)
calls `reportUnavailable()` immediately on every audio `STATE_BUFFERING` event.
That method calls `stopVideoPlayback()`, which stops and clears the video media
source, even if video was healthy. The eight-second buffering timeout controls
the subsequent audio restart; it does not delay this video teardown.

When audio becomes ready again, `recoverConnection()` creates a fresh video
session. If this receiver was the final video viewer, the sender also stops its
camera and encoder, then starts both again. Small audio interruptions therefore
cause RTSP negotiation and camera/codec churn, additional battery use, and
longer picture interruptions. This path became possible when 1.1.0 split audio
and video into separate sessions.

The probe sends a single `STATE_BUFFERING` callback and verifies that video
`stop()` and `clearMediaItems()` run immediately while audio itself is untouched.
This establishes the amplification behavior, not the cause of the initial audio
buffer underrun.

**Recommended correction:** track audio buffering independently of video session
eligibility. Keep a healthy video session through brief audio rebuffering, and
tear it down for an intentional pause/disconnect or a confirmed audio-session
replacement. Preserve real connection-loss alarm timing; extending the alarm
delay would not fix this problem.

### 3. A momentary zero-viewer count destroys the standby stream immediately

**Priority: high. Confidence: confirmed behavior; recovery amplifier.
Present before 1.1.0.**

[`handlePlayingClientCountChanged()`](../../app/src/main/java/com/babycam/RtspCameraService.java#L774)
stops all capture and closes the RTSP listener immediately when the last session
leaves a remotely started stream. There is no reconnection grace period. The
20-second `STANDBY_VIEWER_TIMEOUT_MS` applies only to startup when no viewer has
ever connected.

In the receiver's recovery path, video is torn down and the audio media source
is subsequently replaced. If no other viewer remains, this forces the sender
through a complete standby/wake cycle. External clients that renegotiate their
transport are also affected. Two sessions per BabyCam video receiver increase
the number of transitions the lifecycle must handle, although immediate
shutdown itself is older behavior.

The probe delivers a zero-viewer callback to a previously connected stream and
verifies immediate `server.stop()` with no delayed shutdown scheduled.

**Recommended correction:** schedule a short, cancellable last-viewer grace
period, check the actual session count when it expires, and cancel it when a
viewer reconnects. Keep explicit Stop/Disarm immediate. Keep the grace finite so
genuine disconnections still return to standby.

### 4. A short output stall or large video burst can disconnect a client

**Priority: high. Confidence: reproduced under a controlled writer stall;
frequency on the phones unknown. Present in 1.0.**

[`RtspServer.sendRtp()`](../../app/src/main/java/com/babycam/RtspServer.java#L734)
uses a queue of only 256 RTP packets. If an immediate `offer()` fails once, the
producer throws and the caller closes that entire client session. There is no
distinction between transient congestion and a persistently unresponsive
receiver. H.264 fragmentation inserts packets one by one, so a frame can partly
fill the queue before the client is closed.

The probe blocks the output writer after one small packet, then submits a
310,000-byte IDR NAL. Its fragments exceed the queue capacity and the socket is
closed. Approximately 300 KiB of fragmented video can fill an empty queue when
the writer cannot drain. At the configured 1080p bitrate, that capacity is less
than a second of average video data; keyframes are burstier than the average.
This limit is older than the reported regression, but the new camera restart
paths create more opportunities for startup keyframe bursts.

**Recommended correction:** queue complete access units with a bounded byte/time
budget, handle video overload at frame boundaries, and resume decoding from a
keyframe after dropping video. Preserve audio where possible and reserve full
disconnect for sustained failure. Merely making the queue much larger risks
stale live footage. Add disconnect-reason and queue-pressure diagnostics: current
RTSP catch blocks discard these reasons.

### 5. Audio-only remote wake still depends on video initialization

**Priority: medium. Confidence: confirmed path and simulated initialization
failure. Audio-only wake behavior changed in 1.1.0.**

[`startFromRemoteRequest(boolean videoRequested)`](../../app/src/main/java/com/babycam/RtspCameraService.java#L311)
ignores its argument. For a sender configured for video, `START_AUDIO` still
starts H.264 and opens the camera once to prepare SDP. This is documented
bootstrap behavior, and successful bootstrap subsequently suspends video when
nobody selected it.

However, initial H.264/camera startup failures still use the fatal startup catch
block. Only asynchronous video failures after startup and on-demand resume have
the audio-preserving recovery path. The probe makes initial H.264 `start()`
throw: the already-started AAC encoder and RTSP server are both stopped. Thus an
audio-only receiver can repeatedly fail because video cannot initialize.

**Recommended correction:** handle initial video failure as a video capability
failure while retaining healthy audio, and make the SDP accurately represent
the available tracks. Preserve later video demand without forcing an audio
reconnect.

### 6. Paused monitoring does not release the audio RTSP session

**Priority: medium. Confidence: confirmed behavior. Not an explanation for a
sender actually displaying Standby.**

[`onPlayWhenReadyChanged(false, ...)`](../../app/src/main/java/com/babycam/ReceiverService.java#L744)
stops video and alarms but does not stop or clear the audio source. Separately,
the RTSP server's `PAUSE` handler sets `playing=false` but leaves
`countedPlaybackSession` and `playingClientCount` unchanged until close. A paused
client sending keepalives can therefore keep a remotely started sender out of
standby even when it consumes no media.

Two probes confirm that the receiver pause callback leaves audio untouched and
that a real server client executing SETUP → PLAY → PAUSE still contributes one
to the server's reported playing count. Media3's RTSP loading implementation also
does not use the normal load-control maximum as a strict live sample-queue cap:
[Media3 1.11.1 source](https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/exoplayer_rtsp/src/main/java/androidx/media3/exoplayer/rtsp/RtspMediaPeriod.java).

**Recommended correction:** since Resume already constructs a fresh live media
source, release audio while monitoring is intentionally paused. Define viewer
counts consistently, with separate connected-session and active-playback counts
if both are required.

## Other findings from the broader review

- **LAN selection can destabilize listeners on devices with multiple LANs.**
  `LanNetworkMonitor.findAddress()` selects the first matching private address
  from `getAllNetworks()`. It does not retain the current working LAN. A changed
  selection makes `onLanAddressChanged()` close all sessions. This is a possible
  contributor on Wi-Fi/Ethernet or overlapping-network devices, not a reproduced
  single-Wi-Fi failure. Prefer the existing bound network while it remains valid
  and log the reason for actual network-driven restarts.
- **Live settings can split endpoint configuration.** Changing port/credentials
  while streaming writes preferences without replacing the active RTSP server.
  A later control-server reconfiguration can change `currentPort` and discovery
  while the RTSP/talkback listeners still use the old configuration. Keep a
  consistent configuration snapshot for active listeners, or apply changes via
  one controlled restart.
- **External cameras without supported audio are incompatible with the new
  playback dependency.** The primary player disables video and the optional
  video session requires primary audio readiness. A video-only camera cannot
  reach that state. This is separate from BabyCam-to-BabyCam disconnects.
- **Low-latency mode also changes transport.** It selects UDP first through
  `setForceUseRtpTcp(!lowLatency)`, rather than changing only buffer sizes. Both
  sessions have very small rebuffer thresholds. Test TCP/UDP separately before
  attributing loss to a dependency upgrade. This transport coupling predates
  the session split. Media3 documents UDP-first behavior and an eight-second
  default RTP inactivity timeout in its
  [RTSP factory](https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/exoplayer_rtsp/src/main/java/androidx/media3/exoplayer/rtsp/RtspMediaSource.java).

## Review coverage and verification

Read all 18 production Java files, the manifest and build configuration, reviewed
layout/resource usage and existing tests, and compared lifecycle changes across
the release history. The updater only runs after user interaction; there is no
streamer-side periodic cloud work or sensor loop explaining idle standby drain.
Normal standby listener threads block on socket I/O. Discovery and control
listeners do not themselves open the camera or microphone for a STATUS request.

Existing screen-off video suspension, last-video-viewer capture suspension,
asynchronous camera failure isolation, and stale-server callback filtering are
already present in 1.2.1. The previous review's screen-off video issue should
not be reported again as an unfixed defect in this checkout. No evidence from
this review establishes the Media3 1.11.0 → 1.11.1 upgrade as the cause.

Verification:

- 35 existing unit tests passed on an actual rerun.
- Seven additional isolated probes passed, reproducing the current behaviors
  described above. They assert observations of defects, not successful fixes.
- `lintDebug` passed: zero errors, one dependency-update warning for Mockito.
- No on-device battery measurement, overnight test, or live stream reproduction
  was possible; `adb devices -l` showed no devices.

Probe source and its temporary Gradle init script are in
`/tmp/babycam-audit/java/com/babycam/StandbyStreamAuditTest.java` and
`/tmp/babycam-audit/audit.init.gradle`. They are outside the app source sets for
normal builds. The combined test run was:

```sh
./gradlew testDebugUnitTest --offline -I /tmp/babycam-audit/audit.init.gradle
```

Before changing the standby power policy, capture `adb shell dumpsys power` and
`adb shell dumpsys batterystats com.babycam` on the unplugged sender while it
displays Standby, and check for `com.babycam:stream`. Check
`adb shell dumpsys media.camera` for unexpected capture. For disconnects, collect
`adb logcat -v threadtime -s BabyCam BabyCamReceiver` on both phones during the
same interval. The sender needs additional reason logging to distinguish queue
overflow, read timeout, network restart, intentional teardown and encoder failure.

The first implementation priorities are idle wake-lock ownership, keeping
healthy video through audio rebuffering, and reconnection grace. Then address
frame-aware output congestion and initial video failure isolation. Validate with
an overnight standby battery comparison and screen-off audio/video tests on the
actual phones; unit tests cannot establish standby power or LAN wake reliability.
