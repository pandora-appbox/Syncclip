# Syncclip

A native Linux tray app and native Android app for sharing text, screenshots and files through your own server. Persistent WebSockets deliver clips as they arrive. AES-256-GCM encrypts content on the sending device; the server receives ciphertext only.

## Use it

- **Linux → Android:** copy text, an image, or files from your file manager on Linux. Use **Send files** to choose files directly. If your screenshot program copies a screenshot to the clipboard, Syncclip detects and sends that image automatically. It arrives in Android's clipboard and recent history while the device connection is active.
- **Android → Linux:** share text, images or files to **Syncclip** from another app, use **Choose files**, or tap **Send clipboard** in the app, notification, or Quick Settings. Android opens the app briefly so it can read the clipboard. Received files have **Copy**, **Share**, and **Save as…** actions. Clipboard pasting depends on whether the destination app accepts file/image URIs. Multiple files can be chosen or shared together, up to 20 queued clips.
- **History:** copy any of up to 50 recent clips. Clips and unsent items expire after 24 hours. Text is limited to 64 KB; PNG, JPEG, and WebP images to 8 MB and 24 megapixels. Other file types are supported up to 32 MB each, including empty files. The relay keeps at most 64 MB of referenced attachments, dropping older history when that budget is reached. Folders are not transferred; archive them first.
- **Settings:** the button stays at the top of both apps. It includes connection details, pause controls, clearing and disconnecting. On Android, **Notify when clips arrive** is off by default; turn it on if you want per-sync alerts. The required background connection indicator stays quiet. Android notification and battery settings are also linked here.
- **Language:** choose English, French, Spanish or Russian in **Settings → Language**, or follow the system language. Both clients use the same translation catalogs. Clipboard text and filenames keep their original content.
- **Appearance:** dark icons and a purple theme on both platforms, full-width history sections and image previews, with compact trash controls.
- **Clear now:** **Delete** removes an individual clip; **Clear history** removes shared history from the server and paired devices, including stale queued transfers when they reconnect. Clearing requires a connection. Settings also lets you clear the current device clipboard independently. Saved copies outside Syncclip stay intact.
- **Offline:** up to 20 encrypted clips wait locally and retry after reconnection. Acknowledgements remove them from the queue. An older reconnect snapshot never replaces a pending local clip.
- **Pause:** turn off automatic sharing on Linux to stop clipboard reads and writes, while retaining incoming history. On Android, turn off **Keep devices connected** to stop the service.

Image and file bytes use a separate authenticated HTTPS upload and download path, keeping the live WebSocket messages small. Text continues to sync while images upload. Both attachment bytes and their descriptions, including filenames, are encrypted on the sending device. Original screenshot pixels are preserved.

The Linux app starts at login and stays in the tray when its window closes. Open it again from the application menu. **Settings → Pair another device** shows a QR code for Android.

## Platform behavior

Linux uses GTK 3's native clipboard notifications, not polling. Automatic capture is supported on X11 (including Linux Mint Cinnamon). Wayland clipboard visibility depends on the compositor; use **Send clipboard** if global notifications are restricted. This version has not been verified on Wayland.

Android 10+ prevents ordinary background apps from reading the clipboard. Syncclip uses explicit Share and focused-activity actions, without accessibility permissions, root, or replacing your keyboard. Receiving runs in a visible foreground service. Android's Doze mode and vendor battery rules can delay the network connection while the phone sleeps. Allow background activity in Android app settings if needed; opening Syncclip reconnects and fetches missed clips. No always-awake guarantee is made. After reboot, open the Android app once to resume.

References: [Android clipboard access](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data), [connected-device foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device), [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby).

## Install Linux

Debian, Ubuntu, and Linux Mint:

```sh
sudo apt install python3-gi gir1.2-gtk-3.0 python3-venv gir1.2-ayatanaappindicator3-0.1 qrencode
./scripts/install-linux.sh
syncclip
```

The installer creates an app-private Python runtime with the pinned dependencies in `linux/requirements.txt`; GTK comes from the distribution. System Python packages are left intact. Synced images are decoded by Pillow into bounded raw pixels before GTK displays them. Enter your private pairing link. The install script adds the launcher, menu entry, and login autostart. To remove it, delete `~/.local/share/syncclip`, `~/.local/bin/syncclip`, `~/.local/share/applications/dev.syncclip.Linux.desktop`, and `~/.config/autostart/syncclip.desktop`. Use **Disconnect** to clear local pairing/history first.

## Build Android

Requirements: JDK 17, Android SDK 36. The Gradle wrapper downloads Gradle 8.14.6 with a pinned SHA-256 checksum.

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For an installable release signed with your own persistent local key:

```sh
python3 scripts/build-release.py
```

The result is `artifacts/Syncclip-0.4.2.apk`. Keep `private/android-release.keystore` and `private/android-signing.json` backed up privately: future updates must use the same signing key. No signing keys, pairing links, or credentials are committed. A normal debug build uses a different signing key. For local device testing without losing release-app pairing, use `python3 scripts/build-release.py --with-tests`: this signs the release, debug, and instrumentation APKs with the same private key. Then replace the test build with the release APK using `adb install -r`. Do not uninstall the app when updating it.

## Server setup

Use the current [Docker Compose server guide](HOSTING.md) for installation, HTTPS, configuration and backups. It supports home servers and rented servers.

## Privacy and recovery

The random 256-bit pairing secret derives separate authentication and content-encryption keys using HMAC-SHA256. Each clip has a random UUID and 96-bit nonce, with its ID authenticated as additional data. The server sets ordering and retention timestamps, persists before acknowledging, and refuses conflicting duplicate IDs. Authentication travels in an HTTPS header, never a query string. Browser origins are rejected, WebSocket access logs are disabled, and notifications omit clipboard content.

This is a **single trusted device group**: every paired device can read and publish every clip. Treat the pairing link as a credential. Disconnecting removes a device's local keys; to revoke a lost device, generate a new pairing bundle in a separate folder, replace the relay auth hash, and pair remaining devices again. Pairing links themselves are excluded from automatic sharing.

Linux stores its key in a user-only configuration directory; Android wraps it with Android Keystore. History, attachments, and pending queues are encrypted on disk. Android grants individual attachment URIs for pasting and sharing; its read-only content provider decrypts content into a pipe when the receiving app opens it. It does not create exported plaintext attachment files. Thumbnails are held in memory. To put a file on the Linux file-manager clipboard, Syncclip decrypts it into a private `exports/<clip-id>/` cache; this cache is removed when the clip is deleted, cleared, or expires. Explicit **Save as…** copies go to the location you choose and are kept. Linux automatic sharing includes copied passwords and other text; pause sharing when you do not want a clip sent. Apps receiving the system clipboard can still read it according to their OS permissions.

Server expiry is enforced on reads/publishes and swept every minute. Unpublished or no-longer-referenced encrypted uploads are swept after a one-hour grace period; total uploaded attachment storage is capped at 256 MB. Local history is filtered by age; expired ciphertext is removed while the client runs. If a device is off, its local encrypted file is pruned on next launch. Deletion markers and a shared history version prevent older clients' outboxes from restoring removed content. A device's **system clipboard** follows the operating system's lifetime rather than the history retention timer. Reconnect snapshots apply only the newest unseen clip. UUID deduplication covers the retained history window, not an unbounded lifetime.

## Verification

```sh
(cd server && go test -race ./... && go vet ./...)
PYTHONPATH=linux python3 -m unittest discover -s tests -v
(cd android && ./gradlew assembleDebug assembleDebugAndroidTest lintDebug)
```

`CryptoTest` and `FileAndSettingsTest` verify Python-to-Android text/image/file encryption compatibility, quiet settings persistence, and clearing and tamper detection on Android. `scripts/linux-ui-test.py` exercises hidden login startup, reopen, and Settings on the active display using an isolated synthetic profile. `LiveFileTest` and `scripts/file-live-test.py ANDROID_SERIAL` verify the actual Linux file clipboard, native Android URI/Share, byte equality, disabled sync alerts, and deletion of the synthetic files on both devices. Shared clear-all is tested against an isolated relay to preserve real user history. `LiveImageTest` (`-e live_images true`) pairs with `scripts/image-live-test.py ANDROID_SERIAL` to test a screenshot placed on the actual Linux clipboard by an external program, exact pixel equality on Android, Android's native image Share action, and the returned Linux image clipboard. `LiveSyncTest` is opt-in (`-e live true`) and uses synthetic text against a provisioned server alongside `scripts/live-peer.py`; it is skipped in ordinary instrumentation runs. The live scripts need `xclip`; the screenshot script also needs Pillow (`python3-pil`). Language catalogs live in `linux/syncclip/locales/` and are packaged into Android assets directly, without a second translation source. `tests/test_localization.py` checks matching messages and placeholders across all four languages. Never upload the private pairing bundle to CI. The GitHub workflow builds the relay and Android APK and runs platform-independent tests and Android lint.
