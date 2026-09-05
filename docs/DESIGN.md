# Paila / Opal — design and motion notes

## Direction

Restrained finance UI derived from the supplied reference direction: lavender-stone background, white/glass cards, near-black actions, small lime accents and original quiet fabric artwork. No fake charts, bank logos, copied screens, or invented transaction history. The texture is an original AI-generated decorative asset, not a photograph or a security feature.

## Hierarchy and interaction

- First setup states **Rs 1,000 test credit** prominently; only successful server registration grants it.
- Balance and Send/Receive come first. Desktop uses a narrow navigation rail and actual activity, not empty analytics widgets.
- Focused Send replaces the mobile dock with Back to wallet. Shorter code entry and less repeated copy bring the amount and Review action into the normal 390×844 view.
- Review shows amount, named recipient, wallet-ID disclosure, method, fee and note before confirmation. No mandatory slider. Cancel/Escape creates no payment.
- The saved offline packet removes inactive method controls and duplicate success messages so its entire QR and copy action are visible at 390×844. The packet remains immutable.
- Pending incoming receipts are visibly separate from settled activity and say they are not spendable. Cached balance is labeled Saved balance when disconnected.
- Copy exposes actual signed text or a manual-copy fallback. Nearby guidance names Bluetooth, Wi-Fi Direct and NFC without pretending the browser can use them.
- Light/dark/system appearance persists in the actual browser app. The design viewer's theme selection is local to that preview session.

## Tokens

Light: background #EEEDF1; ink #1B211E; muted #606660; card #FFFFFF; soft #F2F3EF; line #DDDFDC; control outline #858D86; lime #DFFF91; lime ink #293622; lavender #E6E2F2.

Dark: background #1C201F; ink #EDF2EB; muted #ACB7AD; card #282E29; soft #333C33; line #424B43; control outline #8A978B; primary #DFFF91; on-primary #23321D.

Browser body type is 16px, small labels at least 14px in the audited routes. Interactive targets in the app audit are at least 44px. Cards generally use 24–28px corners, actions and dock use pills. System fonts require no network download. `qa/contrast-checks.json` measures selected solid-color token pairs only, not a full accessibility certification or image-background contrast audit.

## Motion

- Route: 280ms fade with 10px rise.
- Balance: short 220ms transition between actual values; no invented number-counting animation.
- Bottom sheet: 260ms.
- Press: 160ms, scale .975.
- Shared easing: cubic-bezier(.2,.8,.2,1).
- Native press source uses spring damping .8 and stiffness 550. Native durations honor the OS animator setting; native behavior remains untested.
- Browser reduced-motion preferences stop the nonessential animation. The preview also offers an explicit Reduce motion checkbox.

## Iterations that changed the result

1. Tightened wallet spacing so the first real activity row fits above navigation.
2. Reduced checkout height, removed redundant prompts and the competing mobile dock.
3. Prioritized the saved offline QR by removing repeated status chrome.
4. Replaced ambiguous pre-payment “Confirmed by the server” copy with “Internet required.”
5. Strengthened input borders and long-name wrapping, preserved draft/focus/caret on same-screen updates, and added a real review sheet.
6. Corrected capture handling: a gallery-only dark-theme inheritance error was not an app defect; the actual dark page was rendered and inspected separately.
7. Matched the landing/status page and launcher artwork to Opal. Removed the landing link that led to an APK which does not exist yet.

## Native handoff

Compose source includes the updated palette, artwork, cards, header, dock, persistent appearance, motion and explicit review before device-credential confirmation. Android compilation, pixel comparison, TalkBack, physical radios and performance profiling remain required. Browser captures are not evidence of a native build or pixel-identical native rendering.
