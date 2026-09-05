# Paila — secure offline payment system

**Pay with zero bars.** Paila signs single-use payment notes on-device and delivers them by QR, Bluetooth, Wi-Fi Direct or NFC — settling exactly once when back online. It runs on test credit (Rs 1,000 trial grants), not real money.

Made by **Bishowdeep Bhusal**. Marketing site: [`site/`](site/index.html) (deployed via GitHub Pages).

## Interface v2 — Opal

Start with **Paila-interactive-design.html**: open it in Chrome or another modern browser. It is a self-contained, interactive design viewer, not the live wallet. Switch among ten captured states, choose a theme, replay motion, and open the review sheets. No account or payment is created there. If motion stays off, check the device's reduced-motion setting as well as the viewer checkbox.

The actual test wallet is `web/` served by the included Node server. It creates real test-ledger balances and supports the verified flows described below. The native Android Compose source has also been restyled; it remains uncompiled and needs build/device validation.

**Verified:** 31 server tests, 21 browser payment/UI checks, 30 preview layout checks across ten states, plus individual visual review of the final captures. See `qa/TEST_REPORT.md` and `docs/DESIGN.md`.


**Status: development handoff, NOT a production payment service.**

An original Nepal-oriented native Android wallet implementation, a runnable test-credit ledger, a browser client for end-to-end testing, and deployment/build tooling. There is no existing app being patched: no APK or source attachment was provided with the request.

## Start here

1. Open **START_HERE.md** for the shortest instructions.
2. Read **qa/TEST_REPORT.md** for what actually ran, what failed and was fixed, and what has NOT been tested.
3. Give **OPENCODE_HANDOFF.md** and this whole folder to your local coding assistant to build the APK and finish physical-device validation.

## What is here

- `android/`: Kotlin + Jetpack Compose. Native screens; Android Keystore signing/encryption; camera QR scanner; Bluetooth Classic RFCOMM; Wi-Fi Direct sockets; experimental two-tap NFC HCE exchange; runtime permission and denial handling; OS screen-lock confirmation; saved outbox; background sync via WorkManager.
- `server/`: Node.js 24, built-in SQLite, no runtime npm dependencies. Device-signed API; atomic double-entry test ledger; idempotency; reserved offline notes; first-redemption-wins settlement; replay, expiry, overdraft and daily test-top-up limits.
- `web/`: landing/status page and opt-in browser test wallet. It talks to the real included test server, not fake hardcoded balance data. Native radios are explicitly unavailable here.
- `deploy/`: Docker + Caddy HTTPS configuration. Persistent single-instance SQLite and signing keys.
- `scripts/`: SDK/Gradle bootstrap and direct-APK publishing checks.
- `.github/workflows/`: server tests and Android debug build workflow.
- `qa/`: test code, logs, and status report. The interactive viewer and reviewed v2 screenshots are included.
- `docs/`: protocol, security boundaries and two-phone acceptance checklist.

## Money model in plain language

1. Register once online. The **server**, not the UI, grants your new device wallet **Rs 1,000 test credit**.
2. Online payments debit one wallet and credit the other in one database transaction.
3. Before going offline, reserve an exact-amount note. Those funds leave your available balance and enter server escrow. Up to Rs 500 total may be reserved.
4. Offline, the receiver gives you their signed receive code. You confirm and sign a note for that exact receiver. Your app saves the note as spent **before** sharing it.
5. QR, Bluetooth, Wi-Fi Direct or NFC carries the signed message. These transports do not create money.
6. The recipient saves a **pending** receipt. When connected, the server settles that note once. Only then is the incoming amount spendable.

A note is usable for 24 hours; an already-created payment can be redeemed for a further seven days. Refunds are forbidden until that entire window closes. This is a deliberate trade-off, not a hidden instant-refund feature. Offline notes cannot be split or re-spent by recipients before settlement.

## Non-negotiable limitations

- **No APK was compiled here.** Android SDK, emulator and Gradle were unavailable; Google's SDK host could not resolve from this sandbox. The Android code is uncompiled and unverified. Build errors or device-specific defects may remain.
- **No public deployment was created.** A local test URL is not a public URL. No hosting account/domain was connected. A permanent free backend with persistent storage is not promised.
- **No physical Bluetooth, Wi-Fi Direct, NFC, camera, permission-dialog, or Play Protect testing occurred.** Browser checks are not Android emulator checks.
- **No real NPR moves.** No PSP/bank integration, KYC, deposits, cash-out, phone OTP, merchant acquisition, or NRB license is implemented. The server refuses real-money mode.
- Ordinary compromised phones can clone or double-sign an offline note. Signatures detect tampering, but cannot establish global offline uniqueness. The server settles only the first valid redemption. A later recipient can lose their expected test credit.
- APKs can be reverse-engineered. R8 raises effort; it cannot make code unreadable. Issuer private keys never belong in the APK.
- The current signing key is not cryptographically tied to an individual device-credential prompt. UI confirmation and Keystore storage are useful defenses, not a hardware authorization guarantee.
- No account recovery or key migration. Uninstalling/clearing data loses the test wallet key. Never use this with real funds.

Do not disable Play Protect or sideload a file you cannot verify. Use signed release builds and appropriate distribution channels after testing.
