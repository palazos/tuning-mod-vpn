# tuning-mod-vpn

Winlator Tuning **VPN Rooms** module (Modularization Context v1).

## Dual purpose

1. **Development / sync** — Kotlin, Compose UI, Rust (BoringTun) and resources for VPN Rooms.
2. **On-demand install** — users install `vpn-module.zip` from GitHub Releases (or the APK asset fallback) via **Tuning Modules** in the Library.

## Installable package

```
module.json
lib/arm64-v8a/libwinlator_vpn.so
```

The product APK compiles this tree as Gradle project `:modules:vpn`. The zip only
ships the native library and manifest; Rooms UI appears when the module is installed.

## Product wiring

- Catalog id: `vpn`
- UI contribution: `library.toolbar` / `vpn.rooms` (Rooms button)
- Install path on device: `files/tuning-modules/vpn/`
- Broker credentials stay in the product APK under `assets/vpn/` (not in this repo)

## Layout

```
packaging/module.json
src/main/java/com/winlator/cmod/vpn/
src/main/rust/vpn/
src/main/res/drawable/
build.gradle
```
