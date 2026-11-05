# 5G Proxy — Tailnet Relay

A small companion app that lets any device on your [Tailscale](https://tailscale.com)
(or compatible) tailnet reach a SOCKS5 proxy running on an Android phone, so the
traffic leaves through the phone's **cellular** connection instead of your home
broadband.

It ships alongside [5G Proxy Pro](https://github.com/tokyoxpa3/5G-Proxy-Pro) but
works with **any** SOCKS5 proxy on the phone.

## Why this exists

Android applies per-app VPN by UID. When a tailnet client such as Tailscale runs
on the phone, the proxy app must be *excluded* from the VPN so that its outbound
traffic uses the cellular interface — but once excluded, it can no longer receive
connections arriving on the client's `tun0` interface.

Tailnet Relay is the missing piece:

```
laptop ──tailnet──▶ Relay (inside the VPN) ──127.0.0.1:1080──▶ Proxy ──▶ cellular
```

The relay stays inside the VPN, accepts TCP on the tailnet address and forwards
it to the proxy's loopback listener. The two apps keep separate UIDs, so the
proxy keeps using the cellular exit and the traffic never touches the tethering
interface.

## Requirements

- Android 8.0 (API 26) or newer
- A tailnet client on the phone (Tailscale, or a compatible client that uses
  `100.64.0.0/10` / `fd7a:115c:a1e0::/48`)
- A SOCKS5 proxy listening on the phone (5G Proxy Pro listens on
  `127.0.0.1:1080` by default)

## What it does

- Listens on the tailnet address only (`tun*` + the CGNAT range). It never binds
  to the cellular or LAN interfaces.
- Forwards TCP to a configurable local SOCKS5 upstream (default
  `127.0.0.1:1080`).
- Exposes an optional HTTP control API (`/status`, `/rotate`) protected by a
  bearer token.
- Optionally rotates the cellular IP by toggling airplane mode, forcing the
  modem to re-attach.

## Limitations

- **TCP only.** Plain UDP ASSOCIATE is not implemented, so QUIC, some games and
  VoIP will not work through the relay. The `0x04` UDP-in-TCP extension used by
  [5G Proxy Client](https://github.com/tokyoxpa3/5G-Proxy-Client) is the only UDP
  path.
- Requires an active tailnet; the phone must stay awake and online.
- All clients share the phone's single cellular IP.

## IP rotation backends

`/rotate` tries the following, in order:

1. **OmniADB-Host** — a narrow, fixed-action `ContentProvider`
   (`ping`, `airplane_on`, `airplane_off`, `grant_secure`). The caller UID is
   checked against this app's package name; it is not a general adb shell.
2. **`WRITE_SECURE_SETTINGS`** — only keeps Wi-Fi alive during the toggle. On
   Android 15+ it does **not** switch the radio.
3. **Default digital assistant** — `ACTION_VOICE_CONTROL_AIRPLANE_MODE`, the only
   path without adb that actually toggles the radio.

Every attempt is verified against the radio state (`awaitRadio()`), not against
the value that was written.

## Control API

```bash
curl -H "Authorization: Bearer <token>" http://<tailnet-ip>:8081/status
curl -H "Authorization: Bearer <token>" http://<tailnet-ip>:8081/rotate
```

The token may also be passed as `?token=`. **Anyone holding the token can cut the
phone off the network** — treat it as a secret.

## Build

```bash
./gradlew :app:assembleDebug
```

Release builds are signed only when `keystore.properties` exists in the
repository root; otherwise the output APK is unsigned.

## License

MIT — see [LICENSE](LICENSE).

## Credits

The relay was originally contributed by
[@bokukpark](https://github.com/bokukpark) in
[5G-Proxy-Pro#6](https://github.com/tokyoxpa3/5G-Proxy-Pro/pull/6). This
repository preserves that history and authorship.
