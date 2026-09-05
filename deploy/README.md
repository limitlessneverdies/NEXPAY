# Public test deployment — not performed here

The landing page and payment API share one public HTTPS origin. Other phones use that origin, not localhost. Persistent storage is mandatory: losing the database or issuer key breaks wallets and payment guarantees.

## Docker + Caddy on your approved host

Prerequisites: Docker/Compose, public server, a DNS name pointing to it, open ports 80/443, and permission to deploy. Hosting/domain costs and free-tier availability vary. Nothing here promises permanent free hosting.

```sh
cp deploy/.env.example deploy/.env
# Set DOMAIN to your actual DNS name and TLS_EMAIL to your email.
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --build
```

Caddy provisions TLS. Check your actual public `/health` from an external device. Create two wallets from different phones using that exact HTTPS origin. The browser lab is disabled by default.

No account credentials were connected or deployment commands run in this sandbox. Container configuration is supplied but Docker itself was not available for validation.

## Direct APK

After building and validating a stable signed APK:

```sh
bash scripts/publish-apk.sh /absolute/path/to/signed-app.apk deploy/downloads/paila-test.apk
```

The server then serves `/downloads/paila-test.apk` with an APK MIME type and attachment filename, NOT a source ZIP. Verify from another phone. Android app and server are separate outputs: updating one does not magically update the other.

## Operations before any pilot

- One issuer instance only. Do not copy SQLite to several replicas or delete/recreate the persistent ledger volume.
- `docker compose ... logs` for operational errors. Do not log request bodies, keys or user payment codes.
- Add a trusted reverse-proxy rate limiter and resource limits. Current in-process rate limiting uses the socket peer; behind Caddy, requests share one peer bucket.
- Keep issuer-private.pem and ledger together in an encrypted, access-controlled backup. Use SQLite's backup API or stop the service before taking a consistent snapshot; do not copy only the live main .sqlite file while WAL is active.
- Rotate public TLS certificates automatically. Issuer signing-key rotation requires a designed trust migration; it is not implemented.
- Signed release keystore is separate from the issuer key. Preserve both outside source control, with separate access controls.
- Restore a backup and compare issuer fingerprint and ledger audit before serving traffic.
- Test expiry/grace behavior, device recovery policy and offline loss disclosures before inviting testers.
- This remains test money. Real-money launch needs a licensed model, independent review and considerably more engineering.
