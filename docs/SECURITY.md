# Security model and release blockers

## Defenses implemented, not certification

- Test-only money mode enforced at startup; 100,000 integer paisa welcome grant per registered public key.
- Signed requests, ECDSA P-256 / SHA-256, SPKI public keys and DER signatures. Domain-separated envelope kinds. HTTPS required in release Android.
- Wallet IDs derive from public keys. Registration proves possession. No APK-wide API secret. Native signing keys and AES-GCM storage key live in Android Keystore; exportability is device-provider dependent and hardware backing is NOT guaranteed.
- Android local state is AES-GCM encrypted, associated-data bound and atomically written. Backup exclusion and release-build FLAG_SECURE are configured. Debug builds intentionally allow screenshots for testing. These do not defeat rooted devices, runtime hooking or memory access.
- Persistent mutation IDs reject different requests with a reused ID and return the same outcome for a retry. Requests are fresh within five minutes; re-signing a queued mutation changes only the timestamp, not its opId or business payload.
- Double-entry ledger and account balances updated under BEGIN IMMEDIATE; SQLite WAL + FULL synchronous; append-only journal triggers; reconciliation checks balances, transaction zero sums, supply, and escrow backing.
- Offline sender persists immutable payment before sharing. Recipient validates issuer, owner signature, receiver binding, amount and expiry; writes receipt before ACK. ACK signs the canonical payment hash.
- Escrow reserves value so legitimate clients cannot also spend it online. Outstanding notes capped at Rs 500 test credit. Validity 24 hours; redemption grace seven days; no early refund.
- Recipient credit remains pending until a single first redemption succeeds. Replays return the original settlement or a visible conflict.
- Bounded bodies and frames, timeouts, input validation, no permissive CORS, allowlisted static routes, strict browser CSP, rate limits, no private-key endpoint. The API does not trust X-Forwarded-For; behind one reverse proxy, the in-process rate limit may apply globally. Add a trusted ingress rate limiter before any broader pilot.

## Attacks that are NOT solved

- Offline double-spending on a compromised device. A malicious sender can sign the same reserved note to multiple recipients. Each receipt can verify offline. Only the first settles. This is explicitly demonstrated by an automated test.
- Compromised issuer, compromised server administrator, rollback of the entire issuer database/key state, root-level client malware, malicious accessibility service, screen-overlay attacks, phishing, malicious QR recipient swaps, denial of service, active radio jamming, stolen unlocked phone, coercion, or supply-chain compromise.
- Human identity verification. Display names are self-chosen; key verification does not prove a real person's legal identity. No OTP, account recovery, KYC, sanctions screening, dispute handling, or merchant risk model.
- Proof that a device's clock was correct offline. Signed timestamps are not trusted hardware time. Final validation depends on the issuer's clock and redemption limits.
- Cryptographic spending authorization bound to the exact OS credential confirmation. UI requests Android device confirmation, but signing keys are intentionally not auth-required so background sync can sign. Production design needs an explicit authorization model and separated keys.
- End-to-end confidentiality over every nearby transport. Bluetooth uses secure RFCOMM and Wi-Fi Direct supplies link security; application messages are signed, not end-to-end encrypted. Names, IDs, amounts and activity are personal data. Do not use sensitive real-life notes.
- Full Byzantine/offline consensus, unlimited chained offline re-spending, arbitrary offline change-making, irrevocable offline acceptance, or guaranteed merchant payment.

## Important engineering limits

SQLite is intentionally single-writer/single-instance. Do not scale replicas against copied SQLite files. Production may need a transactional managed database, migrations, monitoring, a hardened API gateway, secure signing hardware, key rotation, recovery, independent audit, regulatory controls and a PSP integration. Node 24's built-in SQLite API may carry an experimental warning; compatibility must be pinned and monitored.

The browser test wallet is not equivalent to Android security. It stores non-exportable WebCrypto keys in IndexedDB and unsigned nonsecret metadata in browser storage; same-origin compromised scripts can still operate those keys. Its test-introspection helper is intentional QA instrumentation; do not expose the lab as a consumer finance product. Browser lab is off by default on deployment.

Android dependencies, build and hardware behavior have NOT been tested in this sandbox. Source completeness is not device readiness.

## Real NPR launch blockers

Legal payment model and NRB-authorized PSP/bank relationship, KYC/AML, safeguarding and reconciliation, fraud limits and offline loss allocation, penetration test, cryptographic review, privacy notice, disputes/refunds, incident response, backups/restore, independent operational security review, current Android distribution requirements, accessibility and physical-device acceptance tests.

## APK reverse engineering

Yes, an APK can be unpacked and inspected. DEX can often be reconstructed into approximate Java/Kotlin-like code; resources, strings, endpoints and native binaries can also be studied. R8 removes names/unused code, not this fundamental property. No size, compiler or 'encryption' flag makes an APK unreadable. Keep business authority and issuer secrets server-side and treat client devices as potentially hostile. Never disable Play Protect as a workaround.
