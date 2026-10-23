# Privacy policy

Effective date: 23 October 2026. Contact: **pandora.appbox@proton.me**.

Syncclip shares text, images and files between devices you pair. You choose and operate the sync server, or choose someone you trust to operate it. There is no Syncclip-hosted account or default relay. This policy describes the app and supplied server software; your server operator and hosting provider may have their own policies.

## What leaves your device

On Linux, automatic sharing reads supported clipboard changes while enabled, including copied passwords or other sensitive text. You can pause it in the app. On Android, sending happens when you use Share, choose files or explicitly send the clipboard. Received clips may be placed on the system clipboard.

Clip text, filenames and attachment contents are encrypted on the sending device with AES-256-GCM before transmission over HTTPS. All devices with the group's pairing secret can decrypt them. The server receives ciphertext, clip identifiers, ordering/timestamps, attachment identifiers and sizes, and authentication information. Encryption does not hide connection addresses, traffic size or timing. The server operator, reverse proxy and hosting/network providers can observe connection metadata such as IP addresses. Their logging and backup settings determine what they retain; the supplied proxy configuration avoids request access logging for the sync endpoint.

The apps contain no advertising, analytics or crash-reporting SDKs and do not send usage reports to the developer. Runtime sync connections go to the server you configure. Installing prerequisites or updates can contact your distribution's package servers, Python package repositories or the download host; these services have their own policies.

## Storage and deletion

Recent history, attachments and pending transfers are encrypted at rest by the apps and relay. Linux keeps the pairing secret in a private user configuration directory; Android protects it using Android Keystore. A running paired app must be able to decrypt content. Host administrators and a compromised device remain trusted boundaries.

History retains at most 50 clips for up to 24 hours. You can delete one clip or clear shared history sooner. Shared deletion reaches other devices when they reconnect; expiry is applied locally when the app runs. Unreferenced encrypted server uploads have a one-hour cleanup grace period. Retention does not securely erase storage media or copies in server backups.

The system clipboard has its own operating-system lifetime. Other apps may read or retain clipboard contents according to OS permissions. Linux creates private temporary plaintext exports when copying attachments to a file-manager clipboard; deletion or expiry removes these exports while the app runs. Files explicitly saved or shared outside Syncclip remain with you or the receiving app and are not removed by clearing Syncclip history.

## Permissions and your choices

Internet/network permissions support sync and reconnecting. Android's foreground-service permission keeps a visible connection running; notification permission controls alerts, and per-clip alerts are optional. Camera permission is used only when scanning a pairing QR code. File selection uses Android's system picker or a file you share; the app does not request broad storage, contacts, location or accessibility access. Android app backup is disabled.

Keep pairing links private: every paired device has access to the whole group. Disconnecting one device removes its local pairing credentials; to revoke access after loss, create a new group secret and re-pair the remaining devices. You can stop the connection, pause Linux automatic sharing, clear history, clear the device clipboard, or uninstall. Saved files and operator backups need separate deletion.

## Contact and changes

For privacy questions or removal of information you have sent directly to the maintainer, email **pandora.appbox@proton.me**. Do not include real clipboard content, pairing links or passwords. The maintainer cannot access or erase data on independently operated servers. Policy changes will be recorded in this file with a new effective date.
