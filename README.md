# KODA v0.4 — BMW ENET ECU Scanner (Android)

KODA is a native Android diagnostic prototype with a compact Georgian-language UI.

## New in v0.4
- **Generic BMW vehicle discovery** rather than a prefilled G20/330i profile.
- **ECU-ების ძიება** button: read-only probe of a bounded list of candidate BMW ECU logical addresses through your existing ENET route. Modules are only shown when a response arrives from the **matching target address**.
- ECU details: target address (hex), model-dependent **possible** module label, read-only response to UDS DID `F190` (VIN), and spare-part/identification DID `F187` if readable.
- **შეცდომების წაკითხვა**: read-only UDS `19 02 FF` fault query on responding modules. Raw 3-byte diagnostic trouble codes and status bytes are shown where supported. No fault clearing is possible.
- **ანგარიშის გაზიარება**: native Android share sheet; VIN is masked in exported text by default.
- Retains wired USB-C ENET and ENET Wi-Fi discovery; HSFZ and DoIP transports.

### What this version does NOT do
- It does NOT modify any ECU, code vehicle options, flash firmware, clear fault memory, run routines, request security access, or change diagnostic sessions.
- It does NOT read BMW CAFD/FDL coding data. This is an ECU/diagnostic inventory milestone, not a BMW coding engine.
- The candidate address list cannot discover every ECU on every BMW. No response DOES NOT prove absence. Addresses and module labels depend on chassis and software.
- Part numbers, fault-code identifiers, and availability vary by control unit. The app does not include licensed BMW fault-description databases.
- This is not a generic all-car OBD/ELM327 scanner. Compatible BMW Ethernet diagnostics only.

## Hardware/validation
- Previous KODA v0.3 was successfully tested by the user with a wired USB-C ENET adapter and a BMW G20, including real HSFZ VIN verification.
- **New ECU scanning and DTC functions are not yet vehicle-tested**. They have 27 automated packet and simulated-gateway tests.
- Android SDK 35, Android minSdk 26; debug APK can be built by the included GitHub Actions workflow.

## Upload and build on GitHub (no Android Studio)
1. Extract this ZIP on your computer.
2. Open your existing KODA repository on GitHub and choose **Add file → Upload files**.
3. Drag the **contents** of the extracted directory to the upload area, preserving `app/src/main/java/...` and `.github/workflows/build-apk.yml` paths.
4. Click **Commit changes** to `main`.
5. Open **Actions → Build KODA Android APK**. The push should start a build automatically. It uses the already-fixed `android-actions/setup-android@v4`.
6. When green, open the successful run → **Artifacts** → download `KODA-v0.4-ECU-Scanner-APK`.
7. Unzip that artifact; inside is `app-debug.apk`.
8. Transfer APK to Honor / Android and install. If you see an app-signature conflict, **uninstall the older KODA debug version first**, then install this one.

## First ECU test
1. Park safely in P, maintain battery power, keep electronics in diagnostic mode.
2. Connect your existing BMW ENET OBD-to-USB-C adapter. On Honor/MagicOS enable Ethernet tethering if needed.
3. Open KODA and connect. Tap **VIN-ის შემოწმება** until connected/verified.
4. Tap **ECU სკანირება → ECU-ების ძიება**. Keep the app foregrounded; a scan can take tens of seconds. Do not unplug ENET while it is running.
5. Review the ECU list and addresses. Tap a row for more details.
6. Optionally tap **შეცდომების წაკითხვა** to request *raw* UDS DTCs from responding modules. Some modules will refuse or not respond; an unsupported response does not mean no faults.
7. Tap **ანგარიშის გაზიარება** to export a VIN-masked report.
8. Send the ECU report and optionally the KODA connection log to the developer. Double check that no personal details are included.

## Protocol notes
- ECU scanning is bounded to a vetted candidate list, and strictly unicast. No functional/broadcast address, system reset, ECU write, erase, memory read, or programming commands are sent.
- Request service IDs: `0x22` for identification DIDs `F190` and `F187`; `0x19` subfunction `0x02`, status mask `0xFF` for read-only DTC query.
- HSFZ source address `F4`, target address according to candidate physical ECU address, TCP 6801.
- DoIP tester `0E80`, routing activation and ISO13400 diagnostic messages; ECU scan coverage depends on vehicle-specific logical-address mapping.
- Use this with a stationary car, adequate 12V battery support, and keep the phone awake. **Coding-write features must be validated separately before being added.**
