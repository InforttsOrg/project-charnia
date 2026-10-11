# Charnia mobile

Flutter client for **Charnia — Infortts Corporate Registry & Swarm Governance**.

The UI is composed from the shared `infortts_shared` package (`InforttsAppShell`,
`InforttsNodeWorkspace`, `AcousticTheme`) plus this app's generated design skin in
`lib/design_skin.dart` (generated — do not edit by hand).

## Layout

- `lib/main.dart` — app entry point (boots the design skin, mounts the shell).
- `lib/design_skin.dart` — generated per-app theme; regenerate via the design pipeline.
- `test/widget_test.dart` — widget smoke test (dashboard/settings shell).
- `android/` — Android host project; requires `local.properties` (flutter.sdk) and,
  for release signing, `android/key.properties` (both untracked).

## Build & test

```sh
flutter pub get          # resolves infortts_shared via path: ../../shared
flutter analyze
flutter test
flutter build apk --release
```

Release/CI validation is gated by the repo-root `./validate-release.sh`; the
Jenkins pipeline owns versioning and CDN publishing. See the top-level `README.md`.
