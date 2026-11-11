# Research notes

## Current architecture

- `:pandora-core` builds the arm64 C++23 payload and Java component factory as
  an unsigned APK.
- The payload has no runtime library dependencies. Its current DEX is 7,224
  bytes and contains only `AppFactory`, its data holder, and the generated `R`
  class.
- `:pandora` builds the signed Android app. A generated asset embeds the core
  APK at `assets/pandora/payload.apk`.
- The builder reads an input APK through Android's document picker, updates its
  binary manifest, appends the payload DEX files and arm64 library, embeds the
  original APK, and exports an unsigned ZIP-aligned APK.
- The original APK is embedded as a slim container (PSL1) whenever the layout
  allows: dropped and DEFLATED entries ride raw, while STORED entries are
  rebuilt from the outer APK at first launch (size and CRC-32 checked per
  entry, SHA-256 checked once for the whole rebuild). The builder proves the
  round-trip against the input before emitting; unverifiable layouts fall back
  to a full `assets/base.apk` copy.
- The payload caches the rebuilt original with a sidecar marker keyed by the
  installed APK size and mtime, so routine launches skip reconstruction.
- An input app's existing `AppComponentFactory` class name is saved in the
  output. Pandora's factory delegates class loading and component creation to
  that original factory.
- `FileHooks.cpp` owns the syscall route table and the `HookOpenAt`-style
  handlers. Each entry hook returns `Continue` or `TraceExit`. `Tracer.cpp`
  captures the entry arguments before the original syscall and passes them to
  exit handlers.
- The `fstat` exit handler resolves the original file descriptor through the
  traced thread's `/proc/<tid>/fd` path. `readlinkat` uses the syscall's
  returned byte count and the caller's buffer size.

## Verification

- AGP 9.4 and Gradle 9.6 build the signed `:pandora:assembleRelease` output.
- The connected Android 16 arm64 device installed and launched the signed
  builder. The on-device archive test passed with an AndroidX component
  factory in the input APK.
- The test output passed `zipalign -c 4`. `aapt dump badging` parsed its binary
  manifest. `apksigner verify` confirmed it remained unsigned.
- The on-device archive test passed after removal of the signature proxy and
  verifies that no signature metadata asset is added.
- A temporary copy signed with the local JKS installed and launched. The
  enforced `CoreRuntimeTest` confirmed tracer activation and matching SHA-256
  digests when reading the installed APK path and the embedded original. The
  signed app was restored afterward.
- AOSP source checks for Android 10 through 17 are in
  `AOSP-COMPATIBILITY.md`. Symbol presence does not verify device behavior.

## Remaining compatibility work

- Re-run the on-device archive and runtime tests against the slim embed path,
  including apps whose data descriptors or ZIP64 layouts force the full-copy
  fallback.
- Run device or emulator tests on Android 10 through 15 and 17. The connected
  device covers Android 16 only.
- Validate apps distributed as split APK sets.
- Exercise native tracing against representative target apps. The AndroidX
  factory launch test does not cover every custom factory implementation.
