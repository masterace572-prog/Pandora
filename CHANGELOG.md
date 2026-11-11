# Changelog

## [Unreleased]

### Added

- Slim embed container (`assets/pandora/slim.apk`, format PSL1): the original
  APK is rebuilt byte-for-byte at first launch from a small recipe plus raw
  spans for only the entries the outer APK cannot reproduce (manifest, signing
  metadata, deflated entries). STORED payloads are recovered from the outer APK
  itself, so the output no longer doubles in size for apps with uncompressed
  native libraries. Every container is verified at patch time by rebuilding the
  original and comparing SHA-256 digests; the patcher falls back to a full copy
  when a layout cannot be slimmed.
- Cold-start cache for the extracted original APK: a sidecar marker keyed by
  the installed APK size/mtime skips re-extraction, removing the per-launch
  copy stall that could freeze startup.
- Tracer watchdog that respawns the redirect tracer if it dies, preventing
  `ENOSYS` failures on every filtered syscall after a tracer crash.
- Patch-time `Deflater.BEST_COMPRESSION` for the output archive.

### Fixed

- Stat spoofing is now consistent: `fstat`, `newfstatat` and `statx` all report
  the embedded original's size, so code that sizes reads by `fstat` no longer
  hits early EOF (a source of `ZipException` crashes) and stat interfaces can
  no longer disagree about the same file.
- Stat patches write only the size/blocks fields instead of rewriting whole
  struct buffers, avoiding overruns of caller-sized `struct stat` buffers.
- The tracer no longer leaves threads permanently stopped when
  `PTRACE_SETOPTIONS` fails; it resumes them and retries on the next stop.
- Attached threads resume immediately after options are set instead of staying
  frozen for the whole attach phase, shortening the startup stall window.
- Path rewriting retries stack scratch slots at several depths and leaves the
  syscall untouched rather than corrupting the stack when no slot is writable.
- `statx` exit handling reads the saved entry arguments again instead of the
  rewrite scratch pointer.
- Runtime extraction replaces the destination atomically and cleans up partial
  outputs on failure.

## [1.0.0] - 2026-11-12

### Added

- Android app for selecting an APK, applying the Pandora core, and exporting an unsigned patched APK.
- Arm64 C++23 core that redirects reads of the installed APK path to an embedded copy of the original APK.
- Original `AppComponentFactory` delegation for apps that already declare one.
- Dark Android interface with an animated glass tab bar, About page, and Pandora launcher icon.
- Two-module Gradle project with `pandora` and `pandora-core`.

### Fixed

- Preserved syscall entry arguments for exit handlers and corrected `fstat` file-descriptor handling.
- Bounded `readlinkat` rewrites by the returned length and caller buffer size.
- Aligned stored APK resources and verified the output with `zipalign`.
- Removed Kotlin and hidden-API library classes from the injected payload DEX.
- Moved release signing to a local JKS and excluded signing material from Git.

### Compatibility

- Builds for Android 10 through 17 on arm64-v8a.
- Tested the APK transformation and redirected APK reads on an Android 16 arm64 device.
- Accepts one APK file. Split APK sets need separate handling.
- Exports patched APKs unsigned. Installation requires signing the exported file.
