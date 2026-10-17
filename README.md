# KODA · Georgian BMW ENET Android (v0.3)

An independent, native Android proof of concept for BMW owners in Georgia. **REAL network discovery and read-only ECU VIN interrogation** for compatible ENET gateways. No coding, flashing, ECU configuration writes, or settings toggles are implemented. It has **not been tested on a physical BMW**, including the 2021 G20; packet handling has been tested against local simulators.

## What is included

- Simplified, dark Georgian-language Android screen built with platform Views (no WebView, no server, no subscription, no cloud services).
- USB-C ENET is the default connection mode. It discovers usable wired Ethernet `Network` objects and `eth*`-style interfaces, including some Android Ethernet tethering setups that are absent from the normal connectivity list. Wi-Fi ENET must be selected explicitly in Advanced settings. Sockets bind to the selected network or wired local IPv4.
- Sends HSFZ discovery (`00 00 00 00 00 11` to UDP 6811) and DoIP vehicle identification requests (0x0001, versions 0x02 and 0x03, UDP 13400). Parses matching responses, VIN, gateway address and MAC/EID.
- If a matching vehicle is found, shows a short connected status; tap **ავტომობილის მონაცემები** to reveal the protocol, gateway IP and reported VIN. This is **discovery**, not proof that ECU reads are available.
- After discovery, the primary connection button changes to **VIN-ის შემოწმება**: opens HSFZ TCP 6801 or DoIP TCP 13400, performs DoIP routing activation (when appropriate), and sends **UDS service 0x22 DID F190 only**. Recognizes ACKs, frame lengths, positive replies and negative UDS replies. Rejects mismatched VIN.
- Technical IP settings and the network log are hidden under **დამატებითი პარამეტრები** to simplify the home screen. No diagnostic payload, command editor or ECU write API exposed.
- Plain Java protocol/core tests including loopback fake BMW gateway servers for HSFZ and DoIP. No external dependencies for the protocol tests.


## Interface simplified in v0.2

- Removed the illustrated car hero, marketing-style headline, colored feature badges and visible raw logs.
- Single primary connection button; after discovery it becomes **VIN-ის შემოწმება**. Once verified it offers reconnection.
- Three simple list entries: **კოდირება** (not yet available), **ავტომობილის მონაცემები**, and **დამატებითი პარამეტრები**.
- Discovery/ECU VIN read use the **same existing ENET network engine** as v0.1. No backend feature or ECU write capability was added.
- `preview/KODA-v0.2-Preview.html` is a standalone browser design preview. It uses **simulated connection states** and features a **future coding menu concept**. The concept menu is not installed in the real app.

**Build verification:** The previous v0.2 Android APK built successfully through GitHub Actions. The v0.3 update passed 19 protocol and simulated-gateway tests locally, but has **not** been compiled into an Android APK or tested on a physical BMW from this environment. Build v0.3 using GitHub Actions.

## Wired ENET connection update (v0.3, Honor 200 Lite)

The previous v0.2 could fall back to Wi-Fi when wired Ethernet tethering did not expose an Android `Network` object. The new version defaults to wired-only, enumerates wired interfaces directly, logs Ethernet IPv4 addresses, and tries limited broadcast for discovery. Go to **დამატებითი პარამეტრები** → **კავშირის ტიპი: USB-C ENET კაბელი** and leave Gateway IP blank for the first test. The diagnostic log distinguishes no Ethernet interface, Ethernet without IPv4, and requests sent without a response.

See `USB-ETHERNET-FIX-v0.3.md` for GitHub upload and test instructions.

## Hardware for YOUR 2021 BMW G20

Option A: **Android phone supporting USB host + compatible USB-C Ethernet adapter + an RJ45 ENET-to-OBD cable**. The RJ45 ENET cable goes into the Ethernet adapter (not a serial ELM327 dongle), and the OBD side goes into the parked G20. If the adapter you already use with your laptop is a USB-C Ethernet adapter, test whether your Android phone recognizes it.

Option B: **BMW-compatible ENET Wi-Fi adapter**. Join its Wi-Fi network on Android and use the app. The adapter must expose BMW ENET Ethernet diagnostics (HSFZ and/or DoIP), not generic ELM327 Bluetooth/Wi-Fi OBD commands.

**Important addressing limitation:** BMW ENET connections often use `169.254.x.x/16` link-local IPv4, with no DHCP service. Stock Android devices differ in whether a USB Ethernet adapter automatically gets a usable link-local address. If the phone's Ethernet network has no IPv4 address, the app cannot assign a system-wide static address on ordinary unrooted Android. Configure a compatible static `169.254.x.x / 255.255.0.0` IP if your phone supports Ethernet IP configuration, or use an ENET Wi-Fi adapter that already provides IP connectivity. Manually entering a gateway IP in the app **does not** fix a missing local IPv4 configuration.

## Build as an Android app

Requirements: Android Studio, Android SDK Platform 35, JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3. Device: Android 8.0 (API 26)+. Internet is needed on your computer only for Android Studio to download Gradle/SDK dependencies; the running app sends no cloud requests.

1. Extract this ZIP and open the project folder with Android Studio (`File > Open`).
2. Install SDK Platform 35 if Android Studio asks; sync the project. In Gradle settings use JDK 17 (or a compatible Studio runtime).
3. This source archive includes `gradle/wrapper/gradle-wrapper.properties`, but **does not include `gradle-wrapper.jar`** (the official bootstrap JAR is not available in this build environment). Android Studio may be able to resolve Gradle from the configured version. If it asks for missing Gradle wrapper files, install Gradle 8.9 locally and run `gradle wrapper --gradle-version 8.9`, then resync.
4. Connect an Android phone by USB, enable USB debugging, and press **Run**. Or choose **Build > Build APK(s)**; the debug APK will be created in `app/build/outputs/apk/debug/`.
5. On your phone, connect the USB Ethernet or ENET Wi-Fi adapter to the vehicle. Place the car in Park, engage parking brake, switch ignition ON as required for diagnostics. Do not run diagnostic tests while driving; use stable battery power.
6. Open KODA and tap **დაკავშირება**. Leave manual gateway IP blank for UDP auto discovery. If discovery is blocked by a network, try a known gateway IPv4 address, if you have verified it with another tool.
7. A discovery result displays the gateway VIN, protocol and IP. Tap **VIN-ის შემოწმება** to check the separate read-only TCP diagnostic path. Export the log if it fails, but redact VIN and local IP before sharing publicly.

## Test transport without a BMW

Requires Java 17+ and Bash:

```sh
bash scripts/check_core.sh
```

It compiles the pure-Java client and protocol parser and runs 19 checks, including loopback emulated DoIP/HSFZ discovery and UDS VIN read. Note: loopback fixtures are **not hardware compatibility proof**.

## Source map

- `app/src/main/java/ge/koda/bmw/MainActivity.java` — Georgian UI and asynchronous connection state.
- `app/src/main/java/ge/koda/bmw/net/AndroidEnetNetwork.java` — Android network routing and socket binding.
- `app/src/main/java/ge/koda/bmw/net/EnetClient.java` — actual UDP/TCP discovery, DoIP routing activation and read-only UDS VIN interrogation.
- `app/src/main/java/ge/koda/bmw/protocol/EnetProtocol.java` — pure Java HSFZ/DoIP packet encoding and decoding, input validation.
- `tests/EnetProtocolTests.java` — packet and simulated car end-to-end tests.

## Troubleshooting

**No vehicle found** — Look for an IPv4 address in the connection log, confirm the USB Ethernet adapter is recognized by Android, ensure ENET cable is correct and ignition is awake. On link-local LAN, both ends need correct routing. Android Ethernet often requires a manually configured link-local address.

**Car discovered but VIN verification fails** — UDP identification succeeded but the gateway didn't accept TCP routing activation, the target logical address or VIN DID. Verify the phone network and the adapter with E-Sys/another reliable tester; inspect the app logs. Do not assume every BMW ECU supports the same DID/diagnostic session.

**Wi-Fi has no internet** — That's normal for ENET Wi-Fi; allow Android to remain connected to the network even without internet. The app explicitly binds sockets to the chosen Wi-Fi/Ethernet network.

**Adapter is ELM327** — Not supported. BMW ENET over Ethernet is not the same as generic OBD-II/ELM327 transport.

**Connection lost** — No ECU writes are made in this version, so disconnecting during a request should not leave changed coding settings.

## Coding roadmap (NOT implemented)

Before any coding toggle is enabled, the project needs: trustworthy model-/ECU-/firmware-specific coding metadata (including legally available CAFD/FDL definitions), full and verified backups, precise ECU identification, feature support mapping, power and connection guards, carefully scoped writes, post-write verification, and a supported recovery path. These functions cannot safely be inferred from raw OBD commands or generic labels.

The first future coding pilot should be a **single low-risk reversible option** verified on the actual G20's ECU/software. This repository intentionally contains no write-capable ECU endpoints.

## Research references

- BMW HSFZ discovery and ENET framing: https://github.com/rawmind0/rawenet/blob/master/docs/hsfz-protocol.md
- BMW Ethernet diagnostics packets and HSFZ sources: https://github.com/secdev/scapy/blob/master/scapy/contrib/automotive/bmw/hsfz.py
- ISO 13400 DoIP messages: https://github.com/secdev/scapy/blob/master/scapy/contrib/automotive/doip.py
- Android direct ENET Ethernet/APIPA adapter guidance: https://github.com/BMW-X/ediabaslib2/blob/master/docs/ENET_WiFi_Adapter.md

KODA is an independent research prototype and is not affiliated with or endorsed by BMW AG or BimmerCode.
