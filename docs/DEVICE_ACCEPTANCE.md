# Two-phone acceptance gate — NOT RUN HERE

Use two physical Android phones with screen locks, separate wallet keys, and the same HTTPS issuer. Include Android 11, 12/12L, 13, 14, 15 as available. API 26 minimum needs backward-compatibility testing. Add Android 16 and current Play policy review before public distribution.

- [ ] Clean install, first launch, form errors, valid setup yields Rs 1,000.
- [ ] Setup retry, app kill during signup, restart: no extra grant.
- [ ] Add test credit actually changes server and client balance; repeated/double taps do not repeat the same operation. Daily limit explains next time.
- [ ] Send positive, fractional, zero, negative, over-limit, insufficient-funds, self-send, expired/forged QR cases.
- [ ] Review shows exact recipient/ID/amount. Credential cancellation makes no new payment.
- [ ] Camera permission allow/deny/permanent deny; actual request and payment QR scan on low-end and high-end phones in poor light.
- [ ] Prepare Rs 100 note, disconnect both phones, pay exactly Rs 100 using QR. Receipt remains pending; reconnect settles once.
- [ ] Kill sender immediately after preparing payment. Same packet recoverable in Activity; no note reuse.
- [ ] Kill receiver before/after saving receipt and before/after ACK. Sync retries exactly once.
- [ ] Bluetooth on/off, discovery, paired/unpaired, pairing denial, permission denial, find timeout, wrong peer, disconnect mid-frame, app backgrounding, retry saved packet.
- [ ] Wi-Fi Direct unsupported device, Wi-Fi off, Location mode off, permissions 26–32 versus 33+, receiver group creation, stale group cleanup, sender group-owner mismatch, socket startup race, network handover.
- [ ] NFC not present/off/HCE unavailable, both phones unlocked, first tap reads receiver, credential confirm, second tap streams note, wrong recipient second tap fails, tag loss before/after commit, retry ACK.
- [ ] WorkManager sync after reconnect, foreground sync overlap, Doze and battery restrictions, failed settlement visible.
- [ ] Duplicate note to two recipients: both can be provisionally accepted offline, first settles and second is clearly rejected. Product copy must not claim this is prevented offline.
- [ ] Expired note refused for new offline acceptance; valid earlier receipt settles during grace; no early refund; grace-close refund and redemption do not both succeed.
- [ ] No screen overflows at 320dp, landscape, 200% font scale; TalkBack labels; all controls scroll into view; dark mode contrast.
- [ ] App reconnect uses the public HTTPS URL on mobile data as well as home Wi-Fi. No localhost or emulator-only address in release defaults.
- [ ] APK signer stable across updates; apksigner verification; direct download MIME; normal install and upgrade without losing keys. No Play Protect bypass.
- [ ] Issuer persistent volume survives reboot/redeployment; backup restore rehearsed; no issuer keys or wallet databases in ZIP, APK, logs, or public download routes.

Write exact device model, OS version, test date, result, screenshots/log locations and defect IDs. Unchecked tests are NOT passes.
