# Third-party software

Syncclip's original code is [MIT licensed](Syncclip-MIT.txt). Dependencies retain their own licenses and copyrights; MIT does not replace them. Reviewed for release 0.4.2 on 23 October 2026 using the exact runtime wheels, Maven POMs and embedded archive notices.

## Distributed runtime components

| Component | Version | License | Original notices |
| --- | --- | --- | --- |
| Gorilla WebSocket (relay) | 1.5.3 | BSD-2-Clause | [notice](gorilla-websocket.txt) |
| Go runtime (relay executable) | 1.27.3 | BSD-3-Clause | [notice](Go.txt) |
| OkHttp | 4.12.0 | Apache-2.0 | [license](android/okhttp-LICENSE.txt) |
| Okio / Okio JVM | 3.6.0 | Apache-2.0 | [license](android/okio-LICENSE.txt) |
| Kotlin standard library, common, JDK7/JDK8 adapters | 1.9.10 | Apache-2.0 | [license](android/kotlin-LICENSE.txt) |
| JetBrains annotations | 13.0 | Apache-2.0 | [text](Apache-2.0.txt) |
| ZXing Android Embedded | 4.3.0 | Apache-2.0 | [COPYING](android/zxing-embedded-COPYING.txt) |
| ZXing core | 3.4.1 | Apache-2.0 | [NOTICE](android/zxing-NOTICE.txt), [text](Apache-2.0.txt) |
| cryptography | 50.0.2 | Apache-2.0 OR BSD-3-Clause | [original licenses](python/cryptography/) |
| cffi | 2.1.1 | MIT-0 | [original license](python/cffi/LICENSE) |
| pycparser | 3.12 | BSD-3-Clause | [original license](python/pycparser/LICENSE) |
| websockets | 10.4 | BSD-3-Clause | [original license](python/websockets/LICENSE) |
| Pillow | 13.0.0 | MIT-CMU, plus bundled codec/font-library licenses | [complete wheel notices](python/pillow/LICENSE) |
| OpenSSL (inside cryptography's wheel) | 4.0.3 | Apache-2.0 | [original license](OpenSSL.txt) |

These runtime licenses allow distribution alongside MIT project code when their notices and conditions are retained. No GPL-only component was identified in the application runtime graph. This is a dependency license review, not a claim that third-party code has been relicensed.

The Linux release ships the exact upstream wheel license files, including Pillow's combined notices for its bundled libraries. GTK 3, PyGObject and Ayatana AppIndicator are obtained from the operating system and dynamically used, not copied into the release. Their LGPL terms remain applicable to those libraries. Python, pip and the local virtual environment are supplied by the distribution. qrencode is an optional external GPL-licensed program, invoked separately for QR generation; it is not bundled. The provided tar installer works without qrencode (pairing by link remains available).

## Build tools and server images

The [exact Maven inventory](dependency-inventory.json) covers 139 resolved runtime/build coordinates, including inherited POM licenses and primary-source URLs. All 11 runtime coordinates are Apache-2.0. Build-only dependencies also include BSD, MIT, JDOM, MPL-1.1 and CDDL/GPL-with-Classpath-exception choices; JNA is Apache-2.0 OR LGPL-2.1. These build-only libraries are not included in the release APK. They do not change the license of Syncclip's source. Instrumentation dependencies are test-only and are not shipped in the release APK (AndroidX/JUnit/Hamcrest retain their upstream licenses).

Gradle 8.14.6 and AGP 8.13.1 use Apache-2.0; the wrapper JAR is redistributed under that license. JDK 17 uses GPL-2.0 with the Classpath exception; the JDK is not bundled. Android SDK/platform tools have separate Google terms and are not bundled. GitHub Actions are remote CI tools, not application dependencies.

The relay container uses a scratch runtime with only the statically compiled Go executable, empty data directory and license files. Go's builder image is not part of that runtime. The optional Caddy HTTPS container preserves the official 2.11.7 Alpine runtime image but replaces its executable with the full standard-module build using Go 1.27.3 and pinned patched networking/crypto modules. Caddy is Apache-2.0; original Go dependency notices and the exact compiled dependency inventory are retained under deploy/https/licenses/ and in the image. The upstream OS notices remain intact. Docker Engine and Compose are prerequisites, not bundled software.

## Redistribution

Keep LICENSE, THIRD_PARTY_NOTICES.md and the licenses/ directory with source and binary distributions. Android includes these notices as APK assets. Linux installs them under the user's Syncclip legal directory; the Debian package also includes them under /usr/share/doc/syncclip/. The server archive and relay image retain the relay's notices. Dependency updates require reviewing the inventory and bundled notices again.
