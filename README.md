# CanPilot

An Android app for reverse engineering CAN buses: reads logs and live streams, decodes them with DBC files, and helps find and define new signals.

## What it does

- **Logs**: openpilot `rlog` (plain, `.zst`, `.bz2`), candump logs (the format DashPilot records), Vector ASC, and CSV exports from SavvyCAN and Cabana. Open several route segments at once; they are loaded in order.
- **Live**: a comma device over Wi-Fi (its `can` service over ZeroMQ) or a DashKit on the phone's USB-C port. A live stream can be recorded to `Download/CanPilot/` as a candump log.
- **DBC**: any number of DBC files, each for all buses or one bus. Message names, signal values at the cursor, value names.
- **Finding signals**:
  - A bit grid per message shaded by how often each bit flips, and a history strip of the payload over time.
  - Marks A and B around the moment you did something in the car: *Changed only in A–B* lists the messages, and highlights the bits, that changed then and at no other time.
  - Markers: note what you did while recording; they are written into the recording and come back when it is opened.
  - *Find signal* by value (what the value was at moments you pick) or by a known signal it moves with.
  - *Compare with a log*: the bits that are constant in both logs but not the same, or that change in only one.
  - Suggested fields per message, guessed from how the bits flip, with counters and checksum-like bytes named.
- **Decode everything**: works out the layout of every message without a DBC entry (fields, counters, verified checksums, multiplexing) and names the fields that follow an already decoded signal or changed only at a marker. Each suggestion comes with its evidence and a confidence, to review and add to the DBC.
- **Defining signals**: tap a bit, a suggestion or a search result to start a signal; adjust size, byte order, sign, factor and offset while a plot and a histogram of the whole log update; add value names and multiplexing; save it. Export the DBC when done.
- **Plots**: any signals over time, from one message or several; plots stay up while you move between messages. Pinch to zoom, drag to pan, tap to move the cursor.

## Live sources

- **comma**: the device must publish over ZeroMQ, which openpilot's `cereal/messaging/bridge` does. Enter its IP address (add `:port` to override the port of the `can` service).
- **DashKit over USB**: the app reads the `A,<bus>,<id>,<data>` lines the fork firmware writes to its USB serial port and asks for every frame with an `L,1` line once a second. Firmware that doesn't know `L,1` only sends the few chassis-bus messages it always forwards.

## Building

`./gradlew assembleDebug` (JDK 17). Every push builds an APK in GitHub Actions; it is signed with the debug key in `app/debug.keystore`, so builds install over each other.

The parsers and decoder in `core/` have no Android dependencies and are covered by `./gradlew testDebugUnitTest`.
