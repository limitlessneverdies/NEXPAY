# Paila v1 test protocol

## Units and encoding

Display currency is **test NPR**, protocol code `tNPR`. 100 paisa = 1 test rupee. All money values are positive safe integer paisa; no binary floating point is used in server ledger operations.

Envelope: `p1.<base64url(JSON UTF-8 bytes)>.<base64url(ECDSA DER signature)>`. No padding is accepted. Signature input is exactly `paila:<kind>:v1:<encodedPayload>`. SHA-256, P-256, DER signatures; public keys are SPKI DER encoded as base64url. Different object kinds have separate signature domains. The original encoded JSON is verified, never a reserialized object. Canonically sorted JSON is used only for semantic idempotency and receipt hashing, not as a replacement for verifying the signed bytes.

Wallet ID: `pa_` + first 32 lowercase hex characters of SHA-256(SPKI bytes). Issuer fingerprint: full SHA-256(SPKI bytes). Every payload has `v: 1`.

## Routes

- `GET /health`: status and test mode; no secret or balance.
- `GET /v1/config`: issuer public key, fingerprint, limits.
- `POST /v1/wallets`, body `{ "envelope": "..." }`: `register` domain. Fields publicKey, name, opId, ts. First registration credits 100,000; retries with same key do not.
- `POST /v1/actions`: `request` domain. Fields walletId, op, opId, ts, data. Request authentication uses the registered public key, not a caller-supplied replacement.

Actions:
- state: data `{}`. Returns available balance, up to 500 owner notes and latest 100 ledger entries.
- pay: `{to,amountMinor,note?}`. Settled online transaction.
- topup: `{}`. Adds 100,000 test paisa, once every 24 hours after the first top-up.
- reserve: `{amountMinor}`. Creates exact-value issuer-signed note; reserve moves available balance to escrow.
- redeem: `{payment}`. Authenticated recipient presents signed offline payment.
- reclaim: `{noteId}`. Authenticated owner, only after expiry + seven-day grace.

Mutation idempotency is scoped to wallet + opId. SHA-256 of canonically ordered op/data detects changed payloads under one ID. No automatic new ID on timeout. Server responses to duplicate mutations need not contain the latest balance: clients separately fetch state.

## Offline objects

`receive`, signed by receiver: publicKey, walletId, name, requestId, createdAt, expiresAt. Name is user-selected, not KYC. Receivers and senders must compare the displayed ID.

`voucher`, signed by issuer: issuer fingerprint, id, owner, ownerKey, amount, createdAt, expiresAt. Database tracks the exact certificate and status. A valid signature alone cannot create server liability absent that ledger entry.

`payment`, signed by note owner: voucher (full envelope), to, requestId, createdAt. The full note is transferred; no change or chained spending is supported. Sender marks the note spent in atomic local storage before any QR/radio output.

`ack`, signed by recipient: paymentHash, walletId, status `received_pending`. ACK is receipt confirmation, NOT issuer settlement. Payment hash is SHA-256 of canonical payment payload JSON. Recipient must durably save before ACK.

## Transport framing

QR: native ZXing writer/scanner; request code, then payment code. Text copy/paste is a fallback. Payment QR density requires real-device tests.

Bluetooth: secure RFCOMM service UUID `0c658bf0-1de7-44c6-8a4e-2a9d5418d021`. Receiver listens and requests discoverability; sender discovers or chooses a paired peer; Android pairing occurs as needed. Each UTF-8 message is prefixed with a 4-byte big-endian length in [1,23000]. Sequence receive request → payment → signed ACK.

Wi-Fi Direct: receiver creates a group and listens on TCP 47872; sender joins as client. Same frame/sequence. Group selection and radio permissions must be tested on actual OEM devices. This is a local peer socket, not the public issuer endpoint.

NFC: proprietary AID `F05041494C4101`, category `other`, not EMV. Receiver must keep Paila Receive NFC active and unlocked. First tap reads signed receiver request; sender then confirms. Second tap streams the prepared payment and receives ACK. CLA 0x80; commands 01/02 request length/chunks, 03/04/05 start/write/commit payment, 06/07 ACK length/chunks. Chunks <=200 bytes, bounded total <=23000. Re-taps must use the SAME saved payment, never a fresh note. Hardware validation is outstanding.

## Crash/retry contract

Sender can always display the saved outbox packet for the same recipient; it cannot cancel and reassign an already-shared note. Recipient retries settlement with its saved opId. Backend rejects a different redemption of one note. On reconnect, pending receipts are settled or visibly rejected. Never turn a rejected receipt into spendable balance.
