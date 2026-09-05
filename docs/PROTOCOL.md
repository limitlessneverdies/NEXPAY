# NexPay protocol v0.4 — Secure Transaction Packages with chain of custody

Offline-first payments: signed value moves phone-to-phone with no server at the moment of payment. The server rebuilds the transfer graph on sync, validates every signature, settles exactly once, and logs fraud with attribution.

Prior spec: Paila v1 test protocol (single-hop exact notes). v0.2 added pool reserves and partial redemption. v0.3 became NexPay (Rs 5,000 grants, 50% auto-reserve). **v0.4 adds chained custody transfers, per-holder funding limits, a fraud log, and transport-aware hop caps.**

## Units and encoding

Display currency is NPR with paisa integers (100 paisa = Rs 1). All money values are positive safe integers; no floating point in ledger operations. (Protocol code `tNPR`, mode `test`: demo balance, not real money.)

Envelope: `p1.<base64url(JSON UTF-8)>.<base64url(ECDSA DER signature)>`. Signature input is exactly `paila:<kind>:v1:<encodedPayload>`. SHA-256, P-256, DER signatures; public keys are canonical SPKI DER base64url. Separate signature domains per kind. The original encoded bytes are verified, never a reserialization. Canonical JSON is used only for idempotency digests and receipt hashing.

Wallet ID: `pa_` + first 32 hex chars of SHA-256(SPKI). Issuer fingerprint: full SHA-256(SPKI). Every payload has `v: 1`.

## The STP (Secure Transaction Package)

Every transfer travels as one atomic signed payload:

| Field | Meaning |
|---|---|
| `voucher` / `root` | Issuer-signed reserve note (hop 0 carries it; deeper hops reference `chain[0]`) |
| `fromKey` | Sender's SPKI public key (defaults to note owner on hop 0) |
| `to` | Recipient wallet ID (single-recipient lock) |
| `amountMinor` | Paisa; any value up to what the sender holds (partial spends generate server-issued remainder) |
| `requestId` | 16–80 char nonce; replay-resistant operation identity |
| `createdAt` | Sender timestamp, skew-checked |
| `hop`, `prev`, `chain` | Custody linkage: hop index, previous transfer hash, ancestor envelopes |

## Chain of custody

- Hop 0: note owner signs from a server-issued reserve note.
- Hop N>0: holder signs `{..., hop: N, prev: hash(parent), chain: [...ancestors, parent]}`.
- Each hop is verified **offline** by the receiver: every ancestor signature, sender continuity (`fromKey` matches previous `to`), strictly decreasing-or-equal amounts, hop count, root voucher (issuer, owner binding, 24h window).
- Protocol max: **6 hops**. Practical transport caps: **QR ≤ 2** (code density), **Bluetooth/Wi-Fi ≤ 3** (23KB frames), **NFC ≤ 6** (chunked APDUs). Chains grow ~2.3× per hop by measured size — physics, not policy.
- Opportunistic forwarding: any holder of a pending transfer can forward up to what they received, without redeeming first.

## Two-phase commit (radios)

Receive request → payment → signed ACK. The sender persists the outbox packet **before** transmitting and always re-shares the identical packet (never a fresh note). The receiver durably saves **before** ACKing; duplicate packets return the identical ACK (idempotent by payment hash). Transport loss surfaces as "connection lost, retry" — one safe automatic retry on the sender side — never a raw socket error, never a duplicate spend.

## Settlement and reconciliation

On reconnect the client uploads pending transfers. The server, per transfer:

1. Validates the full chain (signatures, continuity, amounts, hop cap, root window + 7-day grace).
2. Checks family funds: settled amount ≤ reserved pool; remainder auto-returns to the note owner as a new reserve note.
3. Checks holder funding: `forwarded + amount ≤ (owner? root amount) + received` — a forwarder can never pass on more than they received.
4. Records every hop as a graph edge; moves escrow → recipient once; replies are idempotent per operation.

Outcomes: `settled` (once), replay of the same payment (identical original), `DOUBLE_SPEND` / `OVERSPEND` / `FORWARD_LIMIT` rejections — every rejected double-spend writes a `fraud` row naming note, first recipient, and attempter.

## What double-spend protection actually guarantees

- **Prevented:** overspending a note family, replay minting, redirecting a payment, reusing one packet twice for value.
- **Detected + attributed:** a compromised phone signing the same value twice offline — first valid redemption settles, later attempts fail with both identities logged.
- **Not claimed:** preventing a malicious device from *attempting* a fork offline. Prevention happens at settlement — the only place it mathematically can without tamper-proof hardware. StrongBox-backed keys (TEE fallback) raise the cost of key cloning.

## Offline reserve

Rs 5,000 granted at registration: Rs 2,500 available + Rs 2,500 auto-reserved (50% cap, matching the controlled-exposure design). Manual top-up to Rs 5,000 reserved total. Notes expire in 24h; refunds after a further 7-day redemption window; daily Rs 5,000 top-up.

## Routes and actions

- `GET /health`, `GET /v1/config` (issuer key, fingerprint, limits).
- `POST /v1/wallets` (`register`): name + key → Rs 5,000 grant + auto-reserve; idempotent per key.
- `POST /v1/actions` (`request` domain, key-authenticated): `state` (available, reserved, total, notes, journal), `pay` (online), `topup` (daily), `reserve`, `redeem` (chain-aware), `reclaim`.
- Mutation idempotency scoped to wallet + opId, with digest-mismatch conflicts. Duplicate submissions return the original response, never fresh money.

## Objects

- `receive`: receiver-signed request (key, wallet, name, requestId, 24h window). Names are self-chosen; compare wallet IDs.
- `voucher`: issuer-signed note (fingerprint, id, owner, ownerKey, amount, 24h window). Server liability exists only for ledger-tracked certificates — signature alone creates nothing.
- `payment`: owner/holder-signed transfer (above). Senders lock the packet before sharing; receivers save before ACK.
- `ack`: recipient-signed `{paymentHash, received_pending}` — receipt confirmation, NOT settlement.

## Crash/retry contract

Sender re-shows the identical outbox packet; it cannot reassign a shared note. Recipient retries with its saved opId. The backend settles the first valid redemption per funds and rejects the rest visibly. Rejected receipts never become spendable. Remainder change returns to the note owner.

## Gap log (tested, not hidden)

- Radio transports need two physical devices for final validation (single-device flows verified on emulator).
- QR carries ≤ 2 hops; deeper chains use Bluetooth/Wi-Fi/NFC.
- No account recovery; uninstall loses the wallet. No real-money use without a licensed partner and independent review.
