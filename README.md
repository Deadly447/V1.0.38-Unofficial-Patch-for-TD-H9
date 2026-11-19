# H9 Monitor

A small, private Android app that shows the APRS packets your TIDRADIO TD-H9
hears. The radio decodes APRS itself and passes each packet to the phone over
Bluetooth LE (the KISS service added by the custom H9 firmware, M13 and later).
The app lists the stations and keeps a raw packet log.

It can also send and receive APRS text messages, so two people with this app
and a TD-H9 each can message each other radio to radio. Position beaconing
stays on the radio.

## Privacy

- **No internet access at all.** The app does not ask for the INTERNET
  permission, so Android will not let it reach any server: no aprs.fi, no
  APRS-IS, no analytics, no update checks.
- **Messages are saved on the phone only** (private file, no cloud backup).
- **No location permission** on Android 12 and later (Bluetooth scanning is
  declared "never for location"). Android 8–11 require location permission for
  any Bluetooth scan; the app still never reads your location.
- **Nothing is transmitted unless you send a message** (and the automatic
  acknowledgement of a message sent to you).
- **Data stays on the phone.** Heard packets are saved in a private file inside
  the app (last 1,000 packets), excluded from cloud backup and device-to-device
  transfer. "Clear" deletes it.
- **No libraries.** Only the Android framework is used, so there is no
  third-party code in the APK.
- "Open in map app" hands a position to whatever map app you choose (for
  example OsmAnd with offline maps). That app's own privacy rules apply.

## Messaging

1. Tap the menu (top right) → **Settings** and enter your callsign with an
   SSID, for example `KG7LBJ-7`. Use a different SSID from the one the radio
   beacons with, so the radio's own APRS doesn't also answer your messages.
2. Open the **Messages** tab → **New message**, enter the other station's
   callsign and up to 67 characters, and tap **Send**. Or tap a station in the
   Stations list and press **Message**.
3. The phone hands the message to the radio over Bluetooth and the radio
   transmits it on its APRS frequency. The other app acknowledges it and the
   message shows **✓ Delivered**. Without a reply it is sent again after 30 s,
   1, 2 and 4 minutes (5 times in all), then marked as failed; tap it to send
   again.
4. Messages to your callsign pop up as a notification and are acknowledged
   automatically, so the sender sees them as delivered. Repeats of the same
   message are acknowledged but shown only once.

**Settings:**
- **Path** (default `WIDE1-1`) is the digipeater path. `WIDE1-1` lets a nearby
  fill-in digipeater help; for longer range use `WIDE1-1,WIDE2-1`; leave it
  empty for direct only.
- **Keep my messages off the internet** (on by default) adds `NOGATE` to the
  path. iGates that follow the convention (Direwolf, APRSIS32 and most others)
  then don't forward your messages to APRS-IS and aprs.fi. Not every iGate
  honours it, and anyone listening on the frequency can still read them:
  APRS is plain text by law (no encryption on amateur bands).

### Encrypted messages (research and bench testing only)

> **Legal warning.** Sending encrypted messages on amateur frequencies is
> prohibited in the US (47 CFR §97.113(a)(4), messages "encoded for the purpose
> of obscuring their meaning") and in most other countries. GMRS prohibits
> coded messages too. Use this feature into a dummy load, on a bench at very
> low power, or under a license that allows encryption (for example an FCC
> Part 5 experimental license). You are responsible for how you use it.

In a conversation, tap **Encryption** at the top:

- Enter a shared passphrase (at least 8 characters; both people use the same
  one) and tick **Encrypt messages I send**. The dialog shows a key
  fingerprint such as `3FA2-91C0`; check that both phones show the same code.
- **Test on phone** runs a message through every step (encrypt, APRS message,
  AX.25, KISS, decode, decrypt) without transmitting, and shows the exact
  packet that would go on the air and its size.
- Encrypted messages show a 🔒. A message that arrives without the right
  passphrase shows as locked and opens once the passphrase is entered.

How it works: AES-256-GCM, key from PBKDF2-HMAC-SHA256 (120,000 rounds) of the
passphrase. The message text on the air is `ENC1:` followed by base64url of
nonce, ciphertext and tag. The sender and recipient callsigns are bound into
the encryption, so a captured message can't be replayed as coming from, or
going to, another station. Acks, callsigns and the path stay in plain text.
An encrypted message holds up to 120 bytes of text and makes a packet of up
to about 220 bytes, longer than the 67 characters normal APRS clients expect,
so other software may cut it short; it's meant for this app on both ends.
Encrypted messages are saved on the phone as received (still encrypted) and
decrypted only for display. The passphrase-derived key is kept in the app's
private settings.

Messages need the radio connected; a message written while disconnected is
sent as soon as the radio connects. Both radios need the custom firmware
with the Bluetooth KISS service (M13 or later), not the public P1 build.

## Using it

1. Turn the radio on (custom firmware with the Bluetooth KISS service).
2. Open H9 Monitor, tap **Choose radio**, and pick the entry ending in
   **(BLE)**, for example `TD-H9-9362(BLE)`. Do not pair it in Android's
   Bluetooth settings; the radio cannot pair, and the app does not need it.
3. Tap **Connect**. The dot turns green and the status says *Listening on …*.
4. **Stations** shows each station once, newest first: symbol, call, how long
   ago, and whether it was heard direct or through a digipeater. Tap one for
   its position, speed, altitude, comment, weather and recent packets.
5. **Log** shows every packet in order. Tap one for the raw text.

The app keeps listening in the background (there is a notification with a
Stop button) and reconnects by itself if the radio goes out of range or is
switched off and on.

### What was fixed compared with APRSdroid

- **No nRF Connect step.** Before each connection the app scans for the radio
  for a few seconds, so Android learns its address type. This works with any
  firmware that has the KISS service, not only M15.
- **No pairing.** The app never asks the radio to bond (that attempt is what
  made the link drop after 30 seconds).
- **Picks the radio from a scan.** No profile import or typing the address,
  and the list only shows devices actually advertising nearby.
- **Reconnects on its own.** Dropped links (including the radio closing the
  connection) are retried after 3 s, backing off to 30 s.

## Building the APK

The app has to be built on a computer with the Android SDK, or by GitHub.

### Option A: GitHub Actions (no software to install)

1. Create a new **private** repository on GitHub and upload everything in this
   folder (keep the `.github` folder; it holds the build script).
2. Open the repository's **Actions** tab. The *Build APK* job runs on every
   upload (or press *Run workflow*). It runs the decoder tests, then builds.
3. When it finishes (about 5 minutes), open the run and download
   **H9Monitor-apk** from *Artifacts*. Unzip it to get the `.apk`.
4. Copy the APK to the phone and open it to install (allow installing from
   your file manager or browser when Android asks).

**Updating later:** without your own signing key, each GitHub build is signed
with a different throwaway key, so Android will refuse to install a new build
over the old one; uninstall first (this clears the packet log). To avoid that,
make a key once and give it to GitHub:

```sh
keytool -genkeypair -v -keystore h9.jks -alias h9 -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 h9.jks > h9.jks.b64          # on macOS: base64 -i h9.jks -o h9.jks.b64
```

Then in the repository: *Settings → Secrets and variables → Actions → New
repository secret*, and add `KEYSTORE_BASE64` (contents of `h9.jks.b64`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` (`h9`) and `KEY_PASSWORD`. Keep `h9.jks`
somewhere safe and never upload it to the repository.

### Option B: Android Studio

Open this folder in Android Studio, let it sync, then *Build → Build App
Bundle(s) / APK(s) → Build APK(s)*. Or from a terminal with the SDK installed:

```sh
./gradlew testDebugUnitTest assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## What it decodes

Plain and compressed positions, Mic-E (including the status such as
*En Route* and altitude), objects and items, status text, messages (shown,
not answered), weather reports, telemetry and third-party (iGate-relayed)
packets. Anything else is shown as raw text in the log.

The decoder lives in `app/src/main/java/net/kg7lbj/h9monitor/aprs/` and has
no Android code, so it is unit-tested on its own:
`app/src/test/.../DecoderTest.java` includes three frames captured from a
TD-H9 over Bluetooth. During development it was also checked against the
Python `aprslib` decoder on 3,000 generated packets (plain, compressed and
Mic-E) with no differences.

## Status

1.0 (receive) runs on a phone and connects to a TD-H9 on M15. 1.1 added
messaging (sending through the radio confirmed). 1.2 adds encrypted messages
for research. Message and encryption logic have unit tests (simulated phones
sending, acking, retrying, wrong keys, tampering, replay to another station).
Supports Android 8.0 (API 26) and later.

## Files

| Path | What it is |
|---|---|
| `app/src/main/java/.../RadioService.java` | Bluetooth LE connection, scan-then-connect, reconnects |
| `app/src/main/java/.../Repo.java` | Heard stations, connection state, private packet file |
| `app/src/main/java/.../MainActivity.java` | The screen (built in code, no layouts) |
| `app/src/main/java/.../aprs/` | KISS, AX.25 and APRS decoding and encoding |
| `app/src/main/java/.../aprs/Messenger.java` | Message send, ack, retry and duplicate handling |
| `app/src/main/java/.../aprs/Crypto.java` | AES-256-GCM message encryption (research) |
| `.github/workflows/build.yml` | GitHub build |
