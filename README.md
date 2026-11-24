# CanPilot

An Android app for reverse engineering CAN buses: reads logs and live streams, decodes them with DBC files, and helps find and define new signals.

## What it does

- **Logs**: openpilot `rlog` (plain, `.zst`, `.bz2`), candump logs and `ms,bus,id,data` CSV files (the two formats DashPilot records), Vector ASC, and CSV exports from SavvyCAN and Cabana. Open several route segments at once; they are loaded in order.
- **Live**: a comma device over Wi-Fi (its `can` service over ZeroMQ) or a DashKit on the phone's USB-C port. A live stream can be recorded to `Download/CanPilot/` as a candump log.
- **DBC**: any number of DBC files, each for all buses or one bus. Message names, signal values at the cursor, value names.
- **Frames**: a table of the frames themselves. *Stream* lists every frame in order; *Fixed* keeps one row per message with what it last sent at the cursor. The bytes that changed since the message's previous frame are set off. A filter narrows both:

  ```
  bus=1 id=249        terms side by side must all hold; or, not and brackets combine them
  id=100-1FF len>4    ids and data bytes are hexadecimal, a-b is a range
  d0=FF d2&0F!=3      byte 0 is FF, the low nibble of byte 2 is not 3
  t=73.5-75           seconds since the start
  DAS_lssState=FAULT  a DBC signal against a number or one of its value names
  changed             the payload differs from the message's previous frame
  stalk               part of an id, a message name or a signal name
  ```
- **Finding signals**:
  - A bit grid per message shaded by how often each bit flips, and a history strip of the payload over time.
  - Marks A and B around the moment you did something in the car: *Changed only in A–B* lists the messages, and highlights the bits, that changed then and at no other time.
  - Markers: note what you did while recording; they are written into the recording and come back when it is opened.
  - *Find signal* by value (what the value was at moments you pick) or by a known signal it moves with.
  - *Compare with a log*: the bits that are constant in both logs but not the same, or that change in only one.
  - Suggested fields per message, guessed from how the bits flip, with counters and checksum-like bytes named.
- **Decode everything**: works out the layout of every message without a DBC entry (fields, counters, verified checksums, multiplexing) and names the fields that follow an already decoded signal or changed only at a marker. Each suggestion comes with its evidence and a confidence, to review and add to the DBC.
- **Defining signals**: tap a bit, a suggestion or a search result to start a signal; adjust size, byte order, sign, factor and offset while a plot and a histogram of the whole log update; add value names and multiplexing; save it. Export the DBC when done.
- **Plots**: a view of its own for any signals from any messages, on one time axis. Add signals by name; states show as labelled coloured strips, measurements as lines, each with its value at the cursor. Below them, every state change in order, for the whole log or between marks A and B. Pinch to zoom, drag to pan, tap to move the cursor.

## Live sources

- **comma**: the device must publish over ZeroMQ, which openpilot's `cereal/messaging/bridge` does. Enter its IP address (add `:port` to override the port of the `can` service).
- **DashKit over USB**: the app reads the `A,<bus>,<id>,<data>` lines the fork firmware writes to its USB serial port and asks for every frame with an `L,1` line once a second. Firmware that doesn't know `L,1` only sends the few chassis-bus messages it always forwards.

## Building

`./gradlew assembleDebug` (JDK 17). Every push builds an APK in GitHub Actions; it is signed with the debug key in `app/debug.keystore`, so builds install over each other.

The code is in two modules. `core/` is plain Kotlin with no Android in it: the frame store, DBC, log formats, the filter and the analysis, covered by `./gradlew :core:test`. `app/` is the Android app: `AppModel` loads data and ties together the parts that keep their own state (`DbcState`, `TraceState`, `PlotState`, `LiveState`, `SignalState`), and there is one file per screen.
