# NexPay — secure offline payment system

**Money moves. Even with zero bars.**

NexPay signs single-use payment notes on your device and delivers them by **QR, Bluetooth, Wi-Fi Direct or NFC** — settling exactly once when back online. Transfers chain phone-to-phone: a receiver can forward what they received **without redeeming first** — every hop signed, server-verified, fork-checked. Online payments settle instantly with a signed review step.

New wallets start with **Rs 5,000**: Rs 2,500 available, Rs 2,500 auto-reserved for offline. Offline sends can be **any amount** up to the reserved balance — the remainder stays reserved automatically. The Android app (`np.nexpay.wallet`, v0.3.x) ships with the server pre-configured; setup is name-only.

It runs on **demo balance**, not real money. Status: development handoff, **not** a production payment service.

Made by **Bishowdeep Bhusal** — protocol, Android app, ledger server, design and docs, one original work. Public site + downloads: **https://github.com/limitlessneverdies/paila-app**

- Start here: [`START_HERE.md`](START_HERE.md) · Protocol: [`docs/PROTOCOL.md`](docs/PROTOCOL.md) · Security: [`docs/SECURITY.md`](docs/SECURITY.md) · Tests: [`qa/TEST_REPORT.md`](qa/TEST_REPORT.md)

## Why NexPay exists

Payments shouldn't die when the signal does. NexPay's answer: cryptographic notes that are **created online, spent offline across a chain of phones, settled later** — each hop verified on the spot, the full graph reconciled at sync. No trust in the transport, no double-counting, no re-spend.

## Features

**Offline-first core**

- ✍️ **Signed notes (STP)** — ECDSA P-256 over amount, recipient, expiry, nonce and chain linkage. Tampering is caught offline.
- 🔗 **Chain of custody** — hop-linked transfers up to 6 deep (QR ≤ 2, Bluetooth/Wi-Fi ≤ 3, NFC ≤ 6). Forward without redeeming; server rebuilds the graph.
- 🎯 **One-recipient lock** — each transfer verifies for exactly one wallet. Redirection fails.
- 🔂 **Single settlement** — first valid redemption settles; replays return the original; forks fail with both identities in the fraud log.
- ⏳ **Escrow + expiry** — reserved funds leave your balance for escrow (max Rs 5,000), expire in 24 h, refundable only after the 7-day redemption window.

**Four offline transports**

- 📷 QR codes, portrait-locked scanner (camera permission only when scanning)
- 🔵 Bluetooth Classic, secure RFCOMM with pairing, signed ACKs, connection-loss retry without duplicates
- 📶 Wi-Fi Direct sockets, no router or mobile data
- 📲 NFC two-tap exchange (HCE, experimental, clearly labelled)

**Online + sync**

- ⚡ Instant server-settled transfers with review screen
- 🔄 Idempotent queue — one server mutation per opId, survives app kills and retries
- ➕ Rs 5,000 on signup, Rs 2,500 auto offline, daily top-up capped

**Device + interface**

- 🔐 Signing keys in Android Keystore, StrongBox-preferred, AES-GCM encrypted storage, FLAG_SECURE release builds, R8-obfuscated, backup disabled
- 🌓 Opal interface: light-first + dark mode, 200% font scaling, TalkBack labels, 320dp layouts
- 💡 In-app guidance on every Send/Receive method — what to use, when

## How a payment flies

1. **Reserve** — while online, Rs 2,500 is set aside automatically (top up to Rs 5,000). Funds move to escrow.
2. **Sign** — offline, read the receiver's signed code and sign any amount up to your pool. The app locks the packet before sharing.
3. **Forward (optional)** — the receiver can pass value onward offline; each phone checks the full signature chain.
4. **Settle** — reconnect and sync. First valid redemption settles once; forks are rejected and logged.

## Quickstart

**Test server** (Node 24, zero runtime dependencies):

```sh
cd server
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

- **37/37 server tests** — grants, auto-reserve, online/idempotent transfers, partial redemption with remainders, chained custody (settle/forward-limits/hop-cap/tamper), fraud attribution, expiry, refunds, journal immutability, persistence, concurrency + reconciliation.
- **21/21 browser checks** — two-wallet flows, offline exchange, settlement-once, top-up limits, reload persistence, tamper rejection, responsive routes.
- **30/30 preview layout checks** across ten states and three widths.
- **Android**: unit tests, lint and assemble verified locally; emulator install + registration proven. Radios need two physical devices — see [`docs/DEVICE_ACCEPTANCE.md`](docs/DEVICE_ACCEPTANCE.md).

Details, failures fixed, and what was NOT tested: [`qa/TEST_REPORT.md`](qa/TEST_REPORT.md).

## Security, stated plainly

- Balances, escrow and issuer keys live server-side. The app holds no secrets worth stealing. R8 raises reverse-engineering cost; it can't prevent it.
- A compromised phone can sign one note twice offline — first settles, the attempt is logged with both identities. Prevention happens at settlement: the only place it mathematically can.
- Demo balance only. No KYC, no cash-out, no real NPR. No account recovery — uninstalling loses the wallet.
- No real-money use without a licensed partner plus independent security/legal review.

Full model: [`docs/SECURITY.md`](docs/SECURITY.md).

## What's here

- `android/` — native wallet (Compose, Keystore, QR/BT/Wi-Fi/NFC, WorkManager sync)
- `server/` — Node 24 signed-API ledger, SQLite, no runtime deps
- `web/` — landing page + opt-in browser test wallet
- `site/` — earlier marketing source (current site lives in `paila-app`)
- `deploy/` — Docker + Caddy HTTPS config
- `scripts/` — SDK/Gradle bootstrap, APK publishing checks
- `qa/` — tests, logs, reports · `docs/` — protocol, security, acceptance

---

© Bishowdeep Bhusal — all rights reserved. Demo balance only. No real money. Do not disable Play Protect to install test builds.
