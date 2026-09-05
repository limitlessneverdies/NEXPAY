# Start here

## Interface v2 — Opal

Start with **Paila-interactive-design.html**: open it in Chrome or another modern browser. It is a self-contained, interactive design viewer, not the live wallet. Switch among ten captured states, choose a theme, replay motion, and open the review sheets. No account or payment is created there. If motion stays off, check the device's reduced-motion setting as well as the viewer checkbox.

The actual test wallet is `web/` served by the included Node server. It creates real test-ledger balances and supports the verified flows described below. The native Android Compose source has also been restyled; it remains uncompiled and needs build/device validation.

**Verified:** 31 server tests, 21 browser payment/UI checks, 30 preview layout checks across ten states, plus individual visual review of the final captures. See `qa/TEST_REPORT.md` and `docs/DESIGN.md`.


## 1. Run the actual test server

Install Node.js **24 LTS** on your computer. No npm install is needed for the server.

macOS / Linux:

```sh
cd paila/server
npm test
ENABLE_WEB_WALLET=true PUBLIC_ORIGIN=http://127.0.0.1:8787 npm start
```

Windows PowerShell:

```powershell
cd paila\server
npm test
$env:ENABLE_WEB_WALLET='true'
$env:PUBLIC_ORIGIN='http://127.0.0.1:8787'
npm start
```

Open `http://127.0.0.1:8787/lab`. That is a **local browser test**, not a public download link. Use a normal and a private browser window to create two different test wallets. Enter different names. Each gets Rs 5,000 from the server (Rs 2,500 available + Rs 2,500 auto-reserved for offline).

Try Receive → Copy receive code on one wallet, then Send → paste code → amount → Review → Confirm on the other. To test offline: prepare a Rs 100 note first, switch to Offline QR, and exchange the resulting signed payment. Pending receipts become spendable only after Sync settles them.

## 2. Build the native Android app

Open `android/` in Android Studio. Use JDK 17, Android SDK 35, and Gradle 8.9. The project pins known-compatible library/tool versions; it does not claim current Play target-SDK compliance.

Or, with JDK 17/21 and network access:

```sh
bash scripts/build-android.sh
```

Windows with Android Studio already installed:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-android.ps1
```

The scripts bootstrap Gradle and, on Linux/macOS when needed, Android command-line tools. They do not install or remotely control your existing Android Studio.

Expected output **after a successful build**:
`android/app/build/outputs/apk/debug/app-debug.apk`.
That is the APK to install, NOT the project ZIP. Do not rename a ZIP into an APK. The GitHub Actions artifact wrapper is a ZIP too; extract the APK inside before publishing it directly.

Debug builds accept `http://10.0.2.2:8787` in an Android emulator connected to the host test server. **Other physical phones cannot use that emulator address.** Physical phones should use the same public HTTPS server.

## 3. Put the server on a public HTTPS origin

Use `deploy/README.md`. You need a hosting machine/container with persistent storage, a reachable domain (or provider-managed HTTPS hostname), and working networking. These were not available in this sandbox.

After deploying, enter the same `https://your-real-domain` on both phones during wallet setup. No localhost address is bundled into release builds. You can also bake your actual origin into the build:

```sh
gradle -p android -PPAILA_API_URL=https://your-real-domain :app:assembleDebug
```

The placeholder `your-real-domain` is an instruction, not an existing public link.

## 4. Publish a direct APK, not a ZIP

Build and test a signed release APK, then run `scripts/publish-apk.sh` with its actual path. It checks the APK container and, when available, Android's signature verifier. Set `APK_PATH` on the server or place the APK in `server/downloads/paila-test.apk`.

After deployment, `https://your-real-domain/downloads/paila-test.apk` sends:
- `Content-Type: application/vnd.android.package-archive`
- `Content-Disposition: attachment; filename="paila-test.apk"`

Until a real APK exists, that route returns 404. This package does not pretend otherwise.
