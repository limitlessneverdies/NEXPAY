# Repeat the checks

Use Node 24. Server tests need no npm dependencies:

```sh
cd server
npm test
cd ..
```

From `qa/`, install the pinned Playwright development dependency and its Chromium browser:

```sh
npm install
npx playwright install chromium
npm test
```

Do not have another service using port 8787 when running the browser suite. `CHROMIUM_PATH` optionally selects an existing Chromium executable. `QA_OUTPUT` optionally selects the output folder; the default is `qa-output` relative to the current working directory.

Preview checks, from `qa/`:

```sh
node preview-e2e.mjs
```

They do not start a payment server or create test wallets. `PREVIEW_FILE` and `PREVIEW_QA_OUTPUT` optionally override input/output files.

To regenerate the design viewer after updating the real UI, run the browser suite first, then `build-preview.py` with `QA_OUTPUT` pointing to its generated HTML captures. The Python builder needs BeautifulSoup (`python -m pip install beautifulsoup4`). It writes the viewer at the project root. Re-run preview checks and visually inspect the changed states before distributing.

The current evidence files and reviewed screenshots are included. Do not describe Chromium captures or browser radio-guidance checks as native Android validation.
