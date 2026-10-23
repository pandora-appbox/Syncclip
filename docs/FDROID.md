# F-Droid preparation

Syncclip's main F-Droid listing is prepared for upstream-signed, reproducible APKs. F-Droid builds the public source and verifies that it matches the downloadable APK before reusing its signature. This preserves updates between F-Droid and the project's release APKs. The private signing key stays with the maintainer.

## Listing and build recipe

Store descriptions and changelogs live in `fastlane/metadata/android/` in English, French, Spanish and Russian. The English fallback includes a 512-pixel icon and two native Android screenshots, captured in an isolated application with synthetic content. They do not contain real pairing links or clipboard history. The artwork, descriptions and screenshots are covered by the project's MIT license.

After committing the release source, export the recipe:

```sh
python3 scripts/prepare-fdroid.py
```

This creates `artifacts/fdroiddata/metadata/dev.syncclip.yml` pinned to that exact commit. Copy it into an fdroiddata checkout's `metadata/` directory and run:

```sh
fdroid lint -f dev.syncclip
```

Use fdroiddata's current `config/` directory when linting: it supplies the category definitions. The recipe removes `android/app/src/androidTest` before the source scan because it contains a synthetic test ZIP that is not needed to build the release. No runtime source or dependency is ignored. F-Droid uses its trusted Gradle installation instead of the committed wrapper.

Build prerequisites: OpenJDK 17, Gradle 8.14.6, Android platform 36 and build-tools 36.0.0. Runtime dependencies resolve from Google Maven and Maven Central. The Android build accepts an absent signing configuration and produces an unsigned APK. The recipe selects the build server’s `JAVA17_HOME` explicitly; toolchain differences require another reproducibility check.

## Verify the upstream APK

Build and sign the upstream release from a clean Git checkout of the final release commit. Do not build a release from a working directory with uncommitted changes. Keep AGP’s VCS metadata unchanged. The checker uses a Git checkout, rather than only a source archive, so differences caused by Git metadata are included in the comparison. Its result records the exact tested source commit.

Install `apksigcopier==1.1.1` in a temporary Python environment, then run from that environment:

```sh
python scripts/check-reproducible-apk.py artifacts/Syncclip-0.4.2.apk
```

Use `--gradle /path/to/gradle-8.14.6/bin/gradle` to use an existing trusted Gradle installation, and `--sdk /path/to/Android/Sdk` if necessary. The command creates a clean Git checkout at `--ref` (default HEAD), excludes signing variables, compiles without the build cache, copies only the public APK signature, compares the complete files byte-for-byte and verifies the known signing certificate. The private signing key is not copied or used for the rebuild. Results and logs go under `artifacts/reproducibility/` and remain outside Git.

The expected signing certificate SHA-256 is:

```text
aab4ccf9ccc71f938e22c0c2d58a84a977d415a085040d02f6c24718e18cc0bb
```

## Publication remains a separate step

Both source repositories and downloads must remain private until the maintainer approves publication. Preparing or linting the recipe does not submit the app. Once approved, make the GitHub source and release APK anonymously accessible, verify the links without credentials, then open a request or merge request through F-Droid's submission process using the prepared recipe. F-Droid never needs the private pairing bundle or signing keystore.

For the submission description: Syncclip is an original native clipboard/file-sharing app for Linux and Android, using a fully open-source self-hosted relay. It has no default hosted service, advertising, analytics, proprietary SDKs or API-key requirement. It is already used on the maintainer's Linux desktop and Android device; provide that fact when discussing the inclusion policy's age/user exception, without claiming a larger user base.

F-Droid maintainers still review eligibility and run their own isolated builds. Local lint, source/binary scans and signature comparison are preparation evidence, not an F-Droid acceptance decision. Keep future public tags and APKs immutable and increase versionCode for Android updates.

References: [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/), [reproducible builds](https://f-droid.org/docs/Reproducible_Builds/), [metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/).
