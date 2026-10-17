# KODA v0.3 — Honor 200 Lite wired ENET troubleshooting update

This is an Android **source-code** update to v0.2. GitHub Actions builds the APK.
No ECU writes or BMW coding are included.

## What changed
- **Wired mode is the default.** KODA v0.3 never silently falls back to Wi-Fi while wired mode is selected.
- Finds USB Ethernet interfaces directly (e.g. `eth0`) even if Android Ethernet tethering does not expose them in `ConnectivityManager.getAllNetworks()`.
- Binds UDP/TCP sockets to the selected local Ethernet IP address. Where an Android Network object exists, uses its socket binding too.
- Uses limited broadcast (`255.255.255.255`) plus subnet broadcast and link-local broadcast when applicable for discovery.
- Advanced settings include **Connection type** (USB-C ENET / ENET Wi-Fi), a diagnostic report of Ethernet interfaces and addresses, and a copyable connection log.

## How to update your GitHub repo
1. Unzip this archive on your Windows laptop.
2. Visit the root of your existing `KODA` GitHub repository, on `main`.
3. Choose **Add file → Upload files**.
4. Drag the **contents** of the unzipped project folder onto the upload area. GitHub's folder drag-and-drop must preserve subfolders, including `app/src/main/java/ge/koda/bmw/...`.
5. Check GitHub's **changed files** list before committing. You should see updates to `MainActivity.java`, `AndroidEnetNetwork.java`, `EnetClient.java`, and other project files.
6. Commit the changes to `main`, then open **Actions → Build KODA Android APK**.
7. After a green check, download the `KODA-v0.3-Android-APK` artifact, unzip it, and install `app-debug.apk`.
8. Android debug APKs made on different GitHub runners can use different signing keys. If Android says **App not installed**, uninstall old KODA first (it has no important saved car data), then install the new APK.

## Next test (Honor 200 Lite)
1. Park the car, enable vehicle electronics as appropriate, attach BMW OBD → ENET Ethernet → USB-C Ethernet → phone.
2. Enable Ethernet tethering if the phone offers it. Leave car stationary.
3. Open KODA → **დამატებითი პარამეტრები** → choose **USB-C ENET კაბელი**.
4. Leave optional Gateway IP blank.
5. Tap **დაკავშირება** once.
6. Go to Advanced → Connection log and copy it.
7. Redact the complete VIN or any other sensitive detail before sharing the log.

## What the logs mean
- `Ethernet eth0: IPv4 არ არის` — adapter is visible but Android has no usable wired IPv4; the app can't fix Android network configuration.
- `აქტიური Ethernet ინტერფეისი ვერ ვიპოვეთ` — Android cannot see an active Ethernet interface; investigate tethering/adapter/OS support.
- `ქსელი: USB Ethernet ...` then `ERROR` — wired routing is selected, but the gateway didn't respond. The log will show where discovery was sent.
- `BMW ნაპოვნია` — gateway identified. VIN verification remains an independent subsequent step.

Do not use the app to change vehicle modules. This version is read-only and has not yet been confirmed on a real Honor 200 Lite/BMW G20 combination.
