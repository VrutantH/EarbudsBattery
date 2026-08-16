# Earbuds Battery (realme TWS Air 7 / realme / OPPO / OnePlus)

A small Android app to read L/R bud + case battery from TWS earbuds over BLE.

## How it works (realme TWS Air 7 and OPPO/OnePlus buds)

realme, OPPO and OnePlus earbuds (the whole BBK Electronics audio family) do
**not** use a standard Bluetooth battery service, and they **do not push
battery values on their own**. They use a shared proprietary protocol called
**OPO v1** (reverse engineered by the community from HCI snoop logs of the
official HeyMelody / realme Link apps):

1. The app detects the OPO service
   `0000079A-D102-11E1-9B23-00025B00A5A5`.
2. It subscribes to the notify characteristic
   `00000002-0000-1000-8000-00805f9b34fb`.
3. It sends a **HELLO** packet, then a **REGISTER** packet (auth token), then a
   **QUERY_BATTERY** packet on the write characteristic
   `00000001-0000-1000-8000-00805f9b34fb`.
4. The buds answer with a notification like
   `AA 0C 00 00 06 81 ... 03 01 64 02 63 03 58` where the pairs are
   `device_id → percent` (01 = left, 02 = right, 03 = case).
5. The app re-queries every 30 s so the gauges stay live, and auto-reconnects
   when the buds drop the connection (e.g. when you put them in the case).

The app detects this automatically — no manual configuration needed.

### Fallbacks for other earbuds

- **Standard Battery Service** (`0x180F` / `0x2A19`): read automatically.
- **Explorer mode**: lists every GATT characteristic with live hex, so you can
  hunt for a proprietary battery characteristic on any other brand.
- **Dashboard mapping**: save the characteristic UUID + byte positions once
  you've found it. Bytes with bit 7 set are decoded as `percent + charging`.

## Build the APK with GitHub Actions (no Android Studio needed)

1. Push this repository to GitHub (or any git host running Actions).
2. On GitHub: **Actions → "Build APK" → Run workflow** (it also runs
   automatically on every push to `main`).
3. When the job finishes, open the run and download the **earbuds-battery-apk**
   artifact. It contains `app-debug.apk` and `app-release.apk`.
4. Install the APK on your phone (enable "Install unknown apps" for your file
   manager). The release APK is unsigned but installs fine on any phone.

The workflow file is `.github/workflows/build.yml`. It needs no secrets.

## Build locally with Android Studio (alternative)

1. Install Android Studio: https://developer.android.com/studio
2. Open Android Studio → **Open** → select this `EarbudsBattery` folder
   (a Gradle wrapper is included, so no manual Gradle install is needed).
3. Let it sync, plug in your phone with USB debugging enabled, press **Run ▶**.
4. Or **Build → Build APK(s)** and grab
   `app/build/outputs/apk/debug/app-debug.apk`.

## How to use the app with your realme TWS Air 7

1. Pair the buds with your phone normally (Settings → Bluetooth).
2. Open the app → tap your buds in the device list.
3. The app detects the OPO protocol automatically, switches to the Dashboard
   and starts showing left / right / case battery within a couple of seconds.
4. Keep the case lid open while connecting — the buds only answer while they
   are awake. The app re-queries every 30 s and reconnects if the link drops.

### If your buds don't show battery

- Realme Link may use a different REGISTER token on some firmware. If the
  Dashboard stays empty, open the **Explorer** tab and check whether the
  `00000002-...` (or `0200079A-...`) characteristic receives any packets after
  the app's handshake. If not, tell the developer — the token lives in
  `OpoBuds.REGISTER`.
- Some OPPO Enco models (e.g. Enco W31) only report a single system battery
  via AVRCP/HFP, not per-bud values over BLE. In that case the app shows the
  system reading in the status line.