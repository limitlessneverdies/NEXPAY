# Local Android build and deployment handoff

## Preserve the v2 design while completing Android validation

Read `docs/DESIGN.md` and inspect `Paila-interactive-design.html` and `qa/screens/`. Retain the white/stone/lime palette, readable type, tap-to-confirm review, persistent light/dark choice, accessible focus, and reduced-motion behavior. Never animate invented balances or replace pending receipts with success before settlement. The captured images are browser references, not proof that Compose renders identically. Compile, inspect emulator screenshots, and adapt native layout as necessary.


You are continuing an UNCOMPILED native Android development project, not auditing a verified production APK. Work only on this project and explicitly selected emulator/devices. Do not claim tool access, device execution or deployment without evidence. Keep test credit clearly separated from real NPR. Do not expose issuer keys or bypass Play Protect.

## First actions

1. Read README.md, docs/SECURITY.md and qa/TEST_REPORT.md.
2. Back up the project. Inspect the real local OS, Java, Android Studio, SDK, adb, Gradle and attached devices. Get user consent before changing physical-device security settings or deleting data.
3. Run `cd server && npm test` using Node 24.
4. Build `android/` with JDK 17, Gradle 8.9 and SDK 35. Run `testDebugUnitTest`, `lintDebug`, and `assembleDebug`. Fix all compile/runtime errors. Do not suppress lint security findings just to turn CI green.
5. Start the server with persistent data. Keep the browser lab disabled on public hosts unless deliberately testing it.

## Highest-risk native areas to validate

- Activity recreation and process death: preserve one outbox payment, never re-enable its note. Rotation presently returns to the wallet; recover saved packets from Activity.
- First setup: button must register, show Rs 1,000, survive restart, and not grant another Rs 1,000 on retry.
- Request camera permission only for scanning. Denial and permanent denial must explain next steps without loops. QR request and payment size/scanning at distance need real-camera tests.
- Bluetooth: secure RFCOMM pairing, discovery permission variants, discoverability cancellation, discovery timeout, disconnect after sender persists note, reconnect/resend, two-device acknowledgements.
- Wi-Fi Direct: permissions, Location mode, group-creation/removal timing, group-owner roles, retries, socket startup and lifecycle. Current source needs OEM tests; do not market it as verified.
- NFC: Paila-specific non-EMV HCE, two taps, different-recipient rejection, APDU chunk sizes, service/process lifecycle, tag removal and reply visibility. Not bank-card NFC. Test on two HCE-capable devices.
- OS screen-lock confirmation: cancellation creates no new payment. Authentication and payment signing are not hardware-bound to the same crypto operation.
- Background sync and simultaneous foreground sync: only one server mutation per opId; atomic local queue operations; preserve receipts on app kill.
- HTTPS: no redirects, no cleartext release traffic, persistent issuer public-key trust, changed-issuer refusal.
- Accessibility: 200% font size, 320dp widths, TalkBack, all scroll extents, camera activity back navigation, dark mode, no mandatory sliding gestures.

## Deployment gate

Do not publish while native compilation or tests fail. Use a user-approved hosting account, real TLS origin and persistent volume. Run deployment smoke tests, restore a backup, verify issuer key persistence across restarts, and scan the release package for secrets. Create a stable release signing keystore outside the repository and preserve it securely. Build/sign R8 release. Verify with apksigner. Publish the `.apk` file with correct MIME, never the source zip. Return the actual HTTPS health and download URLs only after fetching them successfully from outside the server machine.

## Completion evidence

Provide APK SHA-256 and signer fingerprint, public health URL, direct APK URL, package/version, build log, server test log, screenshots from the Android emulator, exact physical models/Android versions for radio tests, unresolved defects and known security limitations. Do not claim 'perfect', 'undecompilable', 'production payment ready' or 'all devices tested'. No real-money deployment without a licensed partner and independent security/legal review.
