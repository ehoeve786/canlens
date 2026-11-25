# CANLens

An Android app for reverse engineering CAN buses: reads logs and live streams, decodes them with DBC files, and helps find and define new signals.

## What it does

- **One workspace**: four tabs share the time cursor and the selected message. *Messages* lists what is on the bus, *Frames* the frames themselves, *Message* the bits and signals of the selected one, *Plots* its and any other signals over time. The time bar with marks, markers and Record stays under all of them. The search button opens one box that goes to a time, a message, a signal to plot or a filter.
- **Logs**: openpilot `rlog` (plain, `.zst`, `.bz2`), candump logs and `ms,bus,id,data` CSV files (the two formats DashPilot records), Vector ASC, and CSV exports from SavvyCAN and Cabana. Open several route segments at once; they are loaded in order.
- **Live**: a comma device over Wi-Fi (its `can` service over ZeroMQ) or a DashKit on the phone's USB-C port. A live stream can be recorded to `Download/CANLens/` as a candump log. The stream and its recording keep going in the background and with the screen off; a notification shows the progress and has a Stop button. Allow CanPilot unrestricted battery use (asked for once) or Samsung's battery saver may still stop it.
- **DBC**: any number of DBC files, each for all buses or one bus. Message names, signal values at the cursor, value names.
- **Frames**: a table of the frames themselves. *Stream* lists every frame in order; *Fixed* keeps one row per message with what it last sent at the cursor. The bytes that changed since the message's previous frame are set off. Tap a row to move the cursor there and select its message; hold it to open the message. A filter narrows both, used filters stay as chips, and when nothing matches the table says which term is to blame:

  ```
  bus=1 id=249        terms side by side must all hold; or, not and brackets combine them
  id=100-1FF len>4    ids and data bytes are hexadecimal, a-b is a range
  d0=FF d2&0F!=3      byte 0 is FF, the low nibble of byte 2 is not 3
  t=73.5-75           seconds since the start
  DAS_lssState=FAULT  a DBC signal against a number or one of its value names
  changed             the payload differs from the message's previous frame
  stalk               part of an id, a message name or a signal name
  ```
- **Sessions**: the app comes back as it was left: the same logs, plots, marks, markers, cursor and filter. Named sessions (Open › Session) keep separate workspaces. DBC files are shared by all of them.
- **Two recordings of one drive** (Open › Add a log to this timeline): the second log is put on the first one's timeline. Its clock is matched to the first's by the frames both recorded, so the two line up to a few milliseconds even when the recorders' clocks were seconds apart. Its buses are numbered from 10 up (bus 1 becomes 11) and decoded like the bus they came from.
- **Saving**: from Frames, the listed frames (all, or those the filter lets through; optionally only between marks A and B) as a candump log or a `time,addr,bus,data` CSV. From Plots, the plotted signals' values as CSV.
- **Finding signals**:
  - A bit grid per message shaded by how often each bit flips, and a history strip of the payload over time.
  - Marks A and B around the moment you did something in the car: *Changed only in A–B* lists the messages, and highlights the bits, that changed then and at no other time.
  - Markers: note what you did while recording; they are written into the recording and come back when it is opened.
  - *Find signal* by value (what the value was at moments you pick) or by a known signal it moves with.
  - *Compare with a log*: the bits that are constant in both logs but not the same, or that change in only one.
  - Suggested fields per message, guessed from how the bits flip, with counters and checksum-like bytes named.
- **Decode everything**: works out the layout of every message without a DBC entry (fields, counters, verified checksums, multiplexing) and names the fields that follow an already decoded signal or changed only at a marker. Each suggestion comes with its evidence and a confidence, to review and add to the DBC.
- **Defining signals**: tap a bit, a suggestion or a search result to start a signal; the editor opens under the bit grid, which outlines the signal as you change it; adjust size, byte order, sign, factor and offset while a plot and a histogram of the whole log update; add value names and multiplexing; save it. Export the DBC when done.
- **Plots**: a view of its own for any signals from any messages, on one time axis. Add signals by name; states show as labelled coloured strips, measurements as lines, each with its value at the cursor. Below them, every state change in order, for the whole log or between marks A and B. Pinch to zoom, drag to pan, tap to move the cursor. *Overlay* draws all measurements on one chart, each filling the height or, with *One scale*, on one value axis: the way to lay the same signal from two merged recordings over each other.

## Live sources

- **comma**: the device must publish over ZeroMQ, which openpilot's `cereal/messaging/bridge` does. The app finds it on the phone's Wi-Fi or hotspot by its ZeroMQ greeting, and looks again whenever it stops answering at the address it was last found at (a hotspot hands out a new one now and then). An address can still be typed, with `:port` to override the port of the `can` service. When the comma's dashkitd serves the DashKit's chassis bus as candump lines on port 62401, it comes in as bus 3, on the comma's clock.
- **DashKit over USB**: the phone takes the place of the comma 3X on the DashKit's USB-C port. With the `canpilot-link` firmware the app gets every frame of both buses (`L,1` once a second) and the bus error counters. Older firmware only sends the few chassis-bus messages it always forwards.
- **Sending through the DashKit** (Message tab › Send; over USB while the DashKit is the live source, else over Bluetooth, which leaves the DashKit's USB port to the comma 3X and uses the pairing DashPilot made): one frame, the same frame repeated, a message's frames between marks A and B again, or each bit flipped in turn. Transmitting has to be armed. The firmware sends an id the car's own modules also send only right after the car's frame of that id, and drops the frame if no such moment comes: two senders of one id that start together can knock a module off the bus. Flipping bits is offered only while a decoded gear signal says Park, and never for messages that steer, brake, drive, restrain or carry the stalks. Frames go out as written: counters and checksums are not updated.

## Building

`./gradlew assembleDebug` (JDK 17). Every push builds an APK in GitHub Actions; it is signed with the debug key in `app/debug.keystore`, so builds install over each other.

The code is in two modules. `core/` is plain Kotlin with no Android in it: the frame store, DBC, log formats, the filter and the analysis, covered by `./gradlew :core:test`. `app/` is the Android app: `AppModel` loads data and ties together the parts that keep their own state (`DbcState`, `TraceState`, `PlotState`, `LiveState`, `SignalState`), and there is one file per screen.
