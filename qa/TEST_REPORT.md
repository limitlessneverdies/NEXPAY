# Paila v0.2 — verification report

## Result and scope

- **31/31 server tests passed.** `backend-tests.log`.
- **21/21 browser end-to-end checks passed.** `browser-tests.log`, `browser-results.json`.
- **30/30 preview layout checks passed:** ten states at 320, 390 and 1440px. Preview review/cancel/confirm, nearby sheet, themes and normal/reduced motion also passed. No uncaught JavaScript exceptions or external HTTP requests. `preview-results.json`.
- The actual wallet's six routes were audited at 320, 390, 768 and 1440px for horizontal overflow, readable type and 44px control targets. `interface-audit.json`.
- Nineteen final browser/preview/landing captures were individually inspected after iterative fixes. `screens/`.

This is targeted development QA, not a guarantee of correctness, full WCAG certification, security audit, load test or Android device test.

## Server coverage

One initial grant per wallet key; signed requests; exact amounts; idempotency and changed-payload rejection; overdraft prevention; daily top-up limits; exact offline reservations and cap; first-redemption-wins settlement; replay, conflicting recipients, forged/tampered packets, expiration/refund timing; persistence; immutable journal; HTTP restrictions/rate limits; concurrency and hundreds of mixed operations with ledger reconciliation. A deliberate malicious-device test demonstrates the offline double-spend limitation rather than hiding it.

## Actual browser payment flow

Two separate test wallets each receive 100000 paisa from the server. A reviewed Rs 25.75 transfer yields 97425 and 102575. Reserving Rs 100 leaves the sender at 87425. With both contexts disconnected, the receiver saves the offline payment as pending without increasing spendable balance. Reconnection settles it to 112575 once; replay adds nothing. The sender's daily top-up yields 187425; another is disabled. Reload preserves identity, balance and the note lock. A tampered code is rejected.

Additional checks cover persistent appearance; honest nearby guidance; Cancel/Escape without funds or queue mutations; draft preservation across connectivity/viewport changes; actual signed-code copying and disclosure; keyboard focus styling; normal vs reduced motion; presets without premature reservation; responsive routes and no uncaught exceptions.

## Visual checks and fixes

Inspected onboarding, wallet, compact Send, Receive, prepared notes, pending activity, settings, invalid-code error, saved payment, actual dark mode, payment review, nearby guidance, scrolled Receive/Settings/Offline sections, desktop/mobile preview and both full landing layouts.

The first passes exposed cramped wallet activity, excessive checkout height and a saved QR pushed below the initial view by repeated status messages. These were fixed and their replacements rendered and inspected. The final saved QR and its copy action are fully visible at 390×844. A gallery-only dark capture initially inherited the wrong text color; direct rendering of the real dark-themed page verified the actual theme. Long pages and intentionally scrolled detail captures are not horizontal-overflow failures.

## What was NOT tested or delivered

- Android compilation, unit-test execution, lint, APK assembly/signing, emulator, TalkBack and real phones.
- Camera recognition of every dense/long QR, Bluetooth, Wi-Fi Direct, NFC, OEM permission/lifecycle behavior, low-end performance and battery use.
- Public hosting, internet-reachable HTTPS, APK installation/update or real financial integrations.
- Exhaustive malformed-input, concurrency/load, browser/OS, assistive-technology or accessibility testing.

No APK or public service is included. Android source may still contain compile/runtime defects. Pending offline receipts are not guaranteed money: compromised devices can produce conflicting payments and only the first valid redemption settles. Do not use with real funds.

## Reproducibility

Follow `qa/README.md`. The server suite uses temporary test state. The browser runner starts its own local test server on port 8787; stop any existing local server on that port first. It tears down its own test state afterward. Captured names and signed codes belong to disposable test wallets, not live users.
