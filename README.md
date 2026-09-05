# Paila — secure offline payment system

**Money moves. Even with zero bars.**

Paila signs single-use payment notes on your device and delivers them by **QR, Bluetooth, Wi-Fi Direct or NFC** — no internet, no middleman in the moment. When you're back online, the ledger settles each note **exactly once**. Online payments settle instantly with a signed review step.

It runs on **test credit** (Rs 1,000 trial grants), not real money. Status: development handoff, **not** a production payment service.

Made by **Bishowdeep Bhusal** — protocol, Android app, ledger server, design and docs, one original work. Built for Nepal's connected days and the signal-less moments in between.

- Marketing site: [`site/`](site/index.html) (live via GitHub Pages)
- Start here: [`START_HERE.md`](START_HERE.md) · Test report: [`qa/TEST_REPORT.md`](qa/TEST_REPORT.md) · Security: [`docs/SECURITY.md`](docs/SECURITY.md)

## Why Paila exists

Payments shouldn't die when the signal does. Paila's answer: cryptographic notes that are **created online, spent offline, settled later** — verified by the receiver on the spot, even with no bars. No trust in the transport, no double-counting, no re-spend.

## Features

**Offline-first core**

- ✍️ **Signed notes** — ECDSA P-256 over amount, recipient, expiry and issuer. Tampering is caught offline.
- 🎯 **One-recipient lock** — each note verifies for exactly one wallet. Redirection fails.
- 🔂 **Single-use notes** — no splitting, no re-spend. First valid redemption settles; replays return the original result.
- ⏳ **Escrow + expiry** — reserved funds leave your balance for escrow (max Rs 500), expire in 24 h, refundable only after the 7-day redemption window.

**Four offline transports**

- 📷 QR codes (camera permission only when scanning)
- 🔵 Bluetooth Classic, secure RFCOMM with pairing + signed acknowledgements
- 📶 Wi-Fi Direct sockets, no router or mobile data
- 📲 NFC two-tap exchange (HCE, experimental, clearly labelled)

**Online + sync**

- ⚡ Instant server-settled transfers with review screen
- 🔄 Idempotent queue — one server mutation per opId, survives app kills and retries
- ➕ Rs 1,000 test credit on first registration, daily top-up capped

**Device + interface**

- 🔐 Signing keys in Android Keystore, AES-GCM encrypted local storage, FLAG_SECURE release builds
- 🌓 Opal interface (v2): light-first + dark mode, 200% font scaling, TalkBack labels, 320dp layouts
- 💡 In-app guidance on every Send/Receive method — what to use, when

## How a payment flies

1. **Reserve** — while online, set aside an exact-amount note. Funds move to escrow.
2. **Sign** — offline, read the receiver's signed code and sign a note for exactly them. The app saves it as spent *before* sharing.
3. **Deliver** — QR, Bluetooth, Wi-Fi Direct or NFC. The receiver verifies and saves a pending receipt.
4. **Settle** — reconnect and sync. The ledger credits the recipient once. Only then is it spendable.

## Quickstart

**Test server** (Node 24, zero runtime dependencies):

```sh
cd paila/server
npm test
ENABLE_WEB_WALLET=true PUBLIC_ORIGIN=http://127.0.0.1:8787 npm start
```

Open `http://127.0.0.1:8787/lab` — local browser test wallets, not a public link. Full steps in [`START_HERE.md`](START_HERE.md).

**Android app** (`android/`, Kotlin + Compose; JDK 17+, SDK 35, Gradle 8.9):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-android.ps1
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk` — the APK to install, never a renamed ZIP. Bake a server origin with `-PPAILA_API_URL=https://your-domain`.

**Public hosting**: see [`deploy/README.md`](deploy/README.md). You bring the host, TLS domain and persistent volume. Publish the `.apk` with correct MIME, never the source zip.

## Verification

- **31/31 server tests** — grants, transfers, idempotency, freshness, caps, offline reserve/redeem/replay, expiry, refunds, journal immutability, persistence, concurrency + full reconciliation.
- **21/21 browser checks** — two-wallet flows, offline exchange, settlement-once, top-up limits, reload persistence, tamper rejection, responsive routes.
- **30/30 preview layout checks** across ten states and three widths.
- **Android**: unit tests, lint and assemble verified locally; emulator install + registration proven. Radios need two physical devices — see [`docs/DEVICE_ACCEPTANCE.md`](docs/DEVICE_ACCEPTANCE.md).

Details, failures fixed, and what was NOT tested: [`qa/TEST_REPORT.md`](qa/TEST_REPORT.md).

## Security, stated plainly

- Balances, escrow and issuer keys live server-side. The app holds no secrets worth stealing. R8 raises reverse-engineering cost; it can't prevent it.
- A compromised phone can sign one note twice offline — only the first settles. Demonstrated by test, disclosed in UI.
- Test credit only. No KYC, no cash-out, no real NPR. No account recovery — uninstalling loses the wallet.
- No real-money use without a licensed partner plus independent security/legal review.

Full model: [`docs/SECURITY.md`](docs/SECURITY.md). Protocol: [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## What's here

- `android/` — native wallet (Compose, Keystore, QR/BT/Wi-Fi/NFC, WorkManager sync)
- `server/` — Node 24 signed-API ledger, SQLite, no runtime deps
- `web/` — landing page + opt-in browser test wallet
- `site/` — marketing site source (mirrored to `docs/` for Pages)
- `deploy/` — Docker + Caddy HTTPS config
- `scripts/` — SDK/Gradle bootstrap, APK publishing checks
- `qa/` — tests, logs, reports · `docs/` — protocol, security, acceptance

---

© Bishowdeep Bhusal — all rights reserved. Test credit only. No real money. Do not disable Play Protect to install test builds.
