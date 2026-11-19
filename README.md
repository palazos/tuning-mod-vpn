# tuning-mod-vpn

Winlator Tuning **VPN Rooms** module (Modularization Context v1).

## Dual purpose

1. **Development / sync** — history and releases for the VPN Rooms feature package.
2. **On-demand install** — users install `vpn-module.zip` from GitHub Releases (or the APK asset fallback) via **Tuning Modules** in the Library.

## Package layout

```
module.json
lib/arm64-v8a/libwinlator_vpn.so
```

## Product wiring

- Catalog id: `vpn`
- UI contribution: `library.toolbar` / `vpn.rooms` (Rooms button)
- Uninstall deletes `files/tuning-modules/vpn/` on device and hides Rooms.

Kotlin/UI sources still live in the product tree under `com.winlator.cmod.vpn` during the pilot; the installable package carries the native engine and manifest. Full source extraction into this repo is a later step.
