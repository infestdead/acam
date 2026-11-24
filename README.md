# ACam

A live viewer for your own IP camera, built for watching a sleeping baby.

Most camera apps show you the picture and stop there. ACam adds what you actually want to
know at a glance from across the room: *is anything moving, is there any sound, and is this
picture really live?*

- **Movement highlight.** Pixels that are slowly changing are tinted, so the rise and fall of
  a sleeping child's chest shows up even when it is far too small to notice in the picture.
  Zoom in on the cot and the detector works at a finer scale there.
- **Sound line.** A scrolling line of the sound level, measured against the room's own
  background noise, so a quiet night and a noisy afternoon both read as "flat until something
  happens". Works with the speaker muted.
- **Visual alarms.** The screen pulses red when sound or movement passes a level you set.
  Each alarm has its own switch and its own sensitivity.
- **Liveness you can trust.** A clock that only advances while frames are actually arriving,
  and a red badge the moment they stop. A frozen picture never looks like a calm one.
- **Low latency, pinch to zoom, several cameras.**

No account, no cloud, no analytics. The app talks to your camera and to nothing else.

> **ACam is not a safety or medical device.** It does not measure breathing and must not be
> relied on to detect that a child is unwell or in danger. The movement highlight is a
> visualisation; it can miss things and it can show things that are not there.

> **Flashing light.** The alarms flash the whole screen red. If anyone who may see the screen
> is sensitive to flashing light, switch the alarms off (tap their labels in the drop-down).

## What you need

- Android 7.0 or newer.
- A camera or recorder that offers an **RTSP** stream (`rtsp://…` or `rtsps://…`) reachable
  from the phone: UniFi Protect, Reolink, Tapo, Amcrest, Dahua, Hikvision and most others.
- **H.264 or H.265** video. Sound is tested with **AAC** audio only.

## Setting up

1. Open the app; it asks for a camera straight away.
2. Give it a name and paste the stream address. Where to find the address depends on the
   maker; search for "*your camera* RTSP URL".
3. Tap the camera's name at the top left to switch cameras, add another, or edit one.

The buttons along the top: speaker, sound line, movement highlight, and the sliders that set
how sensitive the highlight and the two alarms are. On every slider, right is more sensitive;
the white dot marks the default.

## Privacy and security

- Camera addresses, which usually contain a password or secret token, are stored only in the
  app's private storage on the phone. They are excluded from keyboard suggestions.
- **Encrypted streams (`rtsps://`).** Cameras nearly always use a self-signed certificate, so
  the usual web-style check would reject them all. ACam instead remembers the certificate a
  camera shows the first time you connect ("trust on first use"). If the camera later shows a
  different one, ACam stops *before sending the address* and asks you. That is expected after
  a camera update or reset; if you did nothing of the kind, be suspicious. Certificates that
  pass the normal public check for their host name are accepted as they are.
- **Unencrypted streams (`rtsp://`)** send the picture, the sound and the password in the
  clear. Use them only on a network you trust, or over a VPN.
- The first connection to an `rtsps://` camera is the one moment trust-on-first-use cannot
  protect: make it on your home network.

## Known limits

- The alarms are visual only and work while the app is on screen. There is no sound or
  vibration alarm and nothing runs in the background yet.
- Developed against UniFi cameras. Other makes will work to the extent their streams are
  standard; please open an issue with the camera model if yours does not.
- Sound has only been tested with AAC. If a camera offers several audio formats, AAC is the
  one requested.
- Streams are played over RTSP-over-TCP only.

## Building

```sh
./gradlew assembleDebug        # needs JDK 17 or newer and the Android SDK
```

The APK lands in `app/build/outputs/apk/debug/`. For a signed release build, put
`ACAM_STORE_FILE`, `ACAM_STORE_PASSWORD`, `ACAM_KEY_ALIAS` and `ACAM_KEY_PASSWORD` in
`~/.gradle/gradle.properties` and run `./gradlew assembleRelease`.

The whole app is one activity in
[`MainActivity.java`](app/src/main/java/io/github/infestdead/acam/MainActivity.java), with no layout XML
and one dependency ([AndroidX Media3](https://github.com/androidx/media)) for playback.

## Licence

ACam is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version. It comes with no warranty.

Copyright © 2026 infestdead.
