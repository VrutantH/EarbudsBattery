# Earbuds Battery (Realme Buds Air T)

A small Android app to read L/R bud + case battery from TWS earbuds over BLE.
Since Realme (like most TWS vendors) doesn't use a standard Bluetooth battery
service, this app has two modes:

- **Explorer** — connects to your earbuds and lists every BLE service/characteristic
  with its live raw bytes, so you can find the one that carries battery data.
- **Dashboard** — once you know the UUID + byte positions, save them here and get
  a live L/R/Case percentage view.

## How to build the real APK

You can't compile this from a chat conversation — you need Android Studio,
which does the actual compiling on your machine, for free.

1. Install **Android Studio** (Ladybug or newer): https://developer.android.com/studio
2. Open Android Studio → **Open** → select this `EarbudsBattery` folder.
3. Let it sync (first sync downloads Gradle + SDK components — needs internet,
   takes a few minutes). If it asks to create a Gradle wrapper, accept.
4. Plug in your phone via USB with **USB debugging** enabled (Settings → About
   phone → tap "Build number" 7 times → Developer Options → USB debugging),
   or use an emulator with Bluetooth support (real device recommended, since
   emulators can't do real BLE).
5. Click the green **Run ▶** button. This installs a real, working APK on
   your device.
6. To get a shareable `.apk` file: **Build → Build Bundle(s)/APK(s) → Build APK(s)**,
   then find it at `app/build/outputs/apk/debug/app-debug.apk`.

## How to actually find the battery characteristic (do this first)

1. Pair your Realme Buds Air T with your phone normally (Settings → Bluetooth), if not already.
2. Open this app → tap your earbuds in the list → it connects and switches to **Explorer**.
3. You'll see a list of every characteristic the earbuds expose. Tap each
   `NOTIFY` or `READ` one — most will be silent/static, but one or two will
   show bytes that change.
4. To identify it with confidence: open the case lid, or use the official
   HeyMelody/Realme Link app to check battery, and watch which characteristic's
   bytes shift at the same time. A common pattern is a single characteristic
   whose payload contains 3+ bytes, where 2-3 of them are plausible battery
   percentages (0-100, or 0-100 with the top bit used as a "charging" flag).
5. Once you've found it: switch to the **Dashboard** tab, paste that
   characteristic's UUID into the "Characteristic UUID" field, and enter which
   byte index (0-based) corresponds to left bud / right bud / case.
6. Tap **Save mapping and subscribe** — the app will remember this and show
   live gauges from then on, every time you reconnect.

### If nothing looks like a battery reading

Some vendors encode battery inside a longer packet with a header/checksum,
not as a plain byte. If none of the raw bytes obviously map to 0-100:

- Note the full hex payload of every characteristic that changes.
- Change *only* the case battery (e.g. let it drain a bit) and diff the bytes
  before/after — the byte(s) that shift are your candidates.
- Some vendors nibble-pack (e.g. low nibble = left, high nibble = right) —
  if raw bytes cap out oddly (e.g. never above 15), try that.

## Known limitation

This app currently supports single-byte-per-value mappings (Dashboard tab).
If your earbuds use nibble-packing or a multi-byte encoding with a checksum,
you'll need a small code tweak in `MainActivity.updateDashboard()` — happy to
extend this once you've found your actual byte layout, since it's specific to
your earbuds' firmware.
