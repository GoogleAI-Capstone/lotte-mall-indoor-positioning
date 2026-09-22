# Lotte Mall Gwanggyo Indoor Positioning App

An Android app that estimates a user's position inside **Lotte Mall Gwanggyo** (Suwon, South Korea) and renders it on a Google Map, switching seamlessly between outdoor GPS positioning and indoor BLE-beacon-based floor detection.

This repository is a **documentation / code-snapshot package**: it contains a full analysis report plus copies of the source files it discusses, extracted from the original Android Studio project (`gps_1`, package `com.example.lottemallindoor`). It is meant as a reference for understanding the floor-detection and coordinate-transform logic, not as a directly buildable Gradle module (only the files relevant to positioning logic are included).

## Core idea

> The horizontal position (X, Y) comes from GPS/BLE scanning, and the floor (Z) is determined separately from BLE beacon RSSI comparisons, then the two are composited on a single screen.

- **Outdoor**: standard GPS via `FusedLocationProviderClient`, plotted directly on Google Maps.
- **Indoor**: GPS lat/lng alone can't tell floors apart, so fixed BLE beacons (the "brain" series) are compared by RSSI to infer which floor the user is on.
- **Map rendering**: once the floor is known, that floor's store layout is drawn as a **vector overlay** (polygons + text labels via `IndoorVectorMap.kt`) on top of Google Maps — not a static image `GroundOverlay`.

## Tech stack

| Category | Details |
|---|---|
| Language / SDK | Kotlin, `minSdk 26`, `compileSdk`/`targetSdk 34` |
| Maps | Google Maps SDK for Android (`play-services-maps:19.0.0`) |
| GPS | `FusedLocationProviderClient` / `LocationRequest` (`play-services-location:21.3.0`) |
| Indoor positioning | `BluetoothLeScanner` — nearest beacon inferred from RSSI |
| Indoor map rendering | Custom vector rendering (`Polygon` + `Marker`), not photo-based overlays |

## Target site

- Location: Lotte Mall Gwanggyo, Yeongtong-gu, Suwon
- Floors covered: B1, 1F, 2F, 3F

## Repository contents

| File | Description |
|---|---|
| [`광교롯데몰_실내측위_정리보고서.pdf`](광교롯데몰_실내측위_정리보고서.pdf) | Full analysis report (Korean) covering architecture, floor-detection logic, the Z-axis (floor) transform, indoor coordinate transform (`FloorPlanProjector`), calibration/debug mode, and a build-verification log. |
| [`code-snapshot/01_MainActivity.kt`](code-snapshot/01_MainActivity.kt) | Main screen controller: GPS updates, BLE scanning, automatic/manual floor switching, calibration debug panel, Google Maps setup. |
| [`code-snapshot/02_IndoorVectorMap.kt`](code-snapshot/02_IndoorVectorMap.kt) | Indoor map data + rendering: pixel↔lat/lng projector (`FloorPlanProjector`), per-floor store/facility data (`FloorStoreSet`), and the `VectorFloorRenderer` that draws polygons/labels at different zoom levels. |
| [`code-snapshot/03_AndroidManifest.xml`](code-snapshot/03_AndroidManifest.xml) | Location/Bluetooth permission declarations and Google Maps API key registration. |
| [`code-snapshot/04_activity_main.xml`](code-snapshot/04_activity_main.xml) | Layout: map fragment, top status dashboard (floor/beacon info), bottom B1/1F/2F/3F manual-switch buttons, and the calibration debug panel. |
| [`code-snapshot/05_app_build.gradle.kts`](code-snapshot/05_app_build.gradle.kts) | Module-level Gradle dependencies (Maps SDK, Location SDK, coroutines, etc.). |
| [`code-snapshot/06_Z축_층간전환_로직.kt`](code-snapshot/06_Z축_층간전환_로직.kt) | Reference excerpt isolating only the floor (Z-axis) detection/switching logic (`beaconMap`, `checkAndSwitchFloor()`, `updateFloorOverlay()`, `rebuildFloorProjectors()`) — not a standalone compilable file. |

## How floor detection works

1. **Beacon → floor mapping** — a fixed table (`beaconMap`) assigns each BLE beacon MAC address to a floor: `brain1–3` → 1F, `brain4–6` → 2F, `brain7–9` → 3F, `brain10` → B1.
2. **Candidate selection** — among beacons seen within the last **4 seconds**, the one with the strongest RSSI is picked as the floor candidate. If nothing was seen recently, the last confirmed floor is kept (no flicker on total signal loss).
3. **Debounce (2 s confirmation)** — a new candidate floor is only confirmed, and the map switched, after it has remained the strongest for **2 continuous seconds** — this prevents false floor switches caused by atrium/escalator openings that let signals leak between floors.
4. **Manual override** — pressing a B1/1F/2F/3F button forces that floor for **10 seconds**, ignoring automatic detection, then returns to automatic mode.

The Z-axis is **not** derived from GPS altitude (indoor GPS altitude error is tens of meters and unusable for floor discrimination). Instead it is a discrete floor index (0/1/2/3) that selects which 2D floor-plan layer (`FloorPlanProjector` + `FloorStoreSet`) is drawn — floor changes are implemented as switching the entire on-screen vector layer, not as continuous 3D movement.

## Indoor coordinate transform (X, Y)

`FloorPlanProjector` converts floor-plan pixel coordinates to lat/lng:

- **Three-point affine fit** (`fromThreePoints`) — three surveyed lat/lng reference points (NW/SE/NE) are paired with their pixel coordinates to solve a 2×2 affine matrix. A two-point fit was rejected because the floor plans are illustrated (not CAD-accurate), so horizontal and vertical scale differ slightly.
- **180° correction** — compensates for a floor plan that was stored upside-down.
- **On-site fine-tuning** — a debug panel exposes rotation (±0.5°) and scale (±0.01×) adjustment buttons to close the gap between the projected and real-world positions, verified against satellite imagery.

## Data notes

| Floor | Plan size (px) | Stores | Notes |
|---|---|---|---|
| B1 (0) | 1259×938 | 33 | Original `floorB1.png` was unavailable; outline is an approximation scaled from the 1F silhouette (should be replaced once the real B1 plan is obtained). |
| 1F (1) | 1312×1031 | 34 | Original plan was rotated 180°; re-oriented to standard coordinates. Includes anchor stores (Uniqlo, Nike, etc.). |
| 2F (2) | 1327×1034 | 66 | Golf/sports brand cluster floor (Adidas, Puma, etc.). |
| 3F (3) | 1351×1022 | 40 | Includes Hi-Mart, Modern House, and a food court. |

## Known issue found & fixed during review

A stray string (`}dlrj`) left at the end of the building-outline mask block in `IndoorVectorMap.kt` (line 644) caused a Kotlin syntax error. It was removed and the fix was verified with `./gradlew compileDebugKotlin --offline` (0 errors).

## Build verification

| Item | Result |
|---|---|
| Kotlin compilation (`compileDebugKotlin --offline`) | ✅ Success (0 errors) |
| Source file wiring (`MainActivity.kt` ↔ `IndoorVectorMap.kt`) | ✅ Correctly referenced |
| Gradle dependencies | ✅ No version conflicts (Maps SDK 19.0.0, Location 21.3.0, coroutines 1.8.1) |
| Manifest permissions | ✅ Location + Bluetooth declared, including API 31+ `BLUETOOTH_SCAN`/`CONNECT` |

Report generated: 2026-09-17.
