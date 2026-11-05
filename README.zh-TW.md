# 5G Proxy — Tailnet Relay

一個小型的陪伴 App：讓 tailnet（Tailscale 或相容客戶端）上的任何裝置，連到手機上
執行的 SOCKS5 代理，出口走手機的**行動網路**，而不是家裡的寬頻。

它與 [5G Proxy Pro](https://github.com/tokyoxpa3/5G-Proxy-Pro) 搭配使用，但上游
可以是手機上的**任何** SOCKS5 代理。

## 為什麼需要它

Android 的 per-app VPN 以 UID 為單位。手機上跑 Tailscale 時，代理 App 必須被
*排除*在 VPN 之外，它的對外流量才會走蜂巢介面；但被排除之後，它又收不到從
Tailscale `tun0` 進來的連線。

Tailnet Relay 就是補上這段：

```
筆電 ──tailnet──▶ Relay（在 VPN 內）──127.0.0.1:1080──▶ 代理 ──▶ 行動網路
```

Relay 留在 VPN 內，在 tailnet 位址上接受 TCP，轉送到代理的 loopback 監聽。
兩個 App 各自擁有獨立 UID，所以代理仍然走蜂巢出口，流量也從不經過熱點介面。

## 需求

- Android 8.0（API 26）以上
- 手機上有 tailnet 客戶端（Tailscale，或使用 `100.64.0.0/10` /
  `fd7a:115c:a1e0::/48` 的相容客戶端）
- 手機上有 SOCKS5 代理在監聽（5G Proxy Pro 預設 `127.0.0.1:1080`）

## 行為

- 只綁 tailnet 位址（`tun*` + CGNAT 範圍），**絕不**綁行動網路或區域網路介面。
- 把 TCP 轉送到可設定的本機 SOCKS5 上游（預設 `127.0.0.1:1080`）。
- 選配 HTTP 控制 API（`/status`、`/rotate`），以權杖保護。
- 選配 IP 輪替：切換飛航模式，強制 modem 重新 attach 以取得新的行動網路 IP。

## 限制

- **只轉 TCP。** 未實作標準 UDP ASSOCIATE，所以 QUIC、部分遊戲與 VoIP 無法
  穿透。唯一支援的 UDP 路徑是 [5G Proxy Client](https://github.com/tokyoxpa3/5G-Proxy-Client)
  使用的 `0x04` UDP-in-TCP 擴充。
- 需要 tailnet 保持連線；手機必須常開、有訊號。
- 所有客戶端共用同一個行動網路 IP。

## IP 輪替後端

`/rotate` 依序嘗試：

1. **OmniADB-Host** — 窄介面、固定動作的 `ContentProvider`
   （`ping`、`airplane_on`、`airplane_off`、`grant_secure`）。以呼叫端 UID
   比對本 App 的套件名，不是通用 adb shell。
2. **`WRITE_SECURE_SETTINGS`** — 只讓切換期間 Wi-Fi 不斷。Android 15+ 上
   它**不會**切換射頻。
3. **預設數位助理** — `ACTION_VOICE_CONTROL_AIRPLANE_MODE`，不靠 adb 時
   唯一真的能切射頻的路徑。

每次嘗試都以射頻狀態（`awaitRadio()`）驗證，不採信寫入的設定值。

## 控制 API

```bash
curl -H "Authorization: Bearer <token>" http://<tailnet-ip>:8081/status
curl -H "Authorization: Bearer <token>" http://<tailnet-ip>:8081/rotate
```

權杖也可用 `?token=` 傳遞。**持有權杖的人可以讓手機斷網**，請當作秘密保管。

## 建置

```bash
./gradlew :app:assembleDebug
```

只有在專案根目錄存在 `keystore.properties` 時才會簽署 release；否則輸出未簽名
APK。

## 授權

MIT — 見 [LICENSE](LICENSE)。

## 致謝

Relay 最初由 [@bokukpark](https://github.com/bokukpark) 在
[5G-Proxy-Pro#6](https://github.com/tokyoxpa3/5G-Proxy-Pro/pull/6) 貢獻。本倉庫
保留了該段歷史與作者資訊。
