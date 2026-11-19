# tuning-mod-vpn

Winlator Tuning **VPN Rooms** module (Modularization Context v1).

## Dual purpose

1. **Development / sync** — Kotlin, Compose UI, Rust (BoringTun) and resources for VPN Rooms.
2. **On-demand install** — users install `vpn-module.zip` from GitHub Releases via **Tuning Modules**.

## Installable package (`vpn-module.zip`)

```
module.json
runtime.jar          # classes.dex + Eclipse Paho MQTT (classes + NLS .properties)
lib/arm64-v8a/libwinlator_vpn.so
```

`runtime.jar` is loaded with `DexClassLoader` so Paho ResourceBundles resolve from the same archive.
Broker credentials stay in the product APK (`assets/vpn/`), not in this repo.

Package version stays **0.1.0** unless the product explicitly bumps it.

## Product wiring

- Catalog id: `vpn`
- UI contribution: `library.toolbar` / `vpn.rooms`
- Install path: `files/tuning-modules/vpn/`
- APK only keeps stubs (`vpn-api`, `VpnBridge`, thin `RoomVpnService`)
