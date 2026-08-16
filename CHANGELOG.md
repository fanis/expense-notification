# Changelog

## Unreleased
- Add a **Show: all / unprocessed only** filter to the review queue that hides processed and skipped candidates. The choice persists across launches, the status line shows how many of the captured notifications are unprocessed, and duplicate hints still compare against everything captured, including items the filter is hiding.

## v1.4.0 - 2026-08-16
- Parse the expanded entries of stacked summary notifications, both InboxStyle lines (email digests, older SMS apps) and MessagingStyle conversation history (modern SMS apps). A payment whose sender only appears inside a summary entry — e.g. a PayPal receipt inside a "2 new messages" email digest, where the line reads "Sender Subject..." — is now captured, and bank SMS missed while notification access was down are recovered from the conversation history, dated to when the message actually arrived when the summary carries per-message timestamps. Each entry is deduplicated by its own content, and an equivalence check (same app, amount, currency, and merchant within 72 hours) keeps a payment seen both as its own notification and as a summary entry from being queued twice.
- PayPal: capture refunds ("You received a refund of ... from ...", "... refunded you ...") and received money ("... sent you ...") as income candidates, and fall back to the "Receipt for Your Payment to ..." subject line when the notification preview cuts off the payment sentence. Money requests are not captured.
- Mark likely cross-source duplicates in the review queue: when another app captured the same amount and currency within 24 hours (e.g. a PayPal receipt email next to the card charge that funded it), the card shows a muted "Possible duplicate — also captured from ..." hint. Nothing is suppressed automatically — two genuinely distinct charges can share an amount and a day — so Skip stays the reviewer's call.
- Add a bundled **PayPal** input config that captures PayPal payment-receipt emails from the notifications of well-known Android email apps (Gmail, Outlook, Samsung Email, Yahoo Mail, Proton Mail, K-9/Thunderbird, and more) — "You paid $12,34 USD to ...", "You sent a/an (automatic) payment of ... to ...", and the classic "This email confirms that you have paid ... $28.99 USD" wording — suggesting **PayPal** as the payment method. Clients that show the raw sender address instead of the display name are matched through `service@paypal.com` / `service@intl.paypal.com` sender titles. Any Unicode currency symbol and any ISO currency code are accepted (e.g. `¥1,500 JPY`), not just €/$/£.
- Add an optional **Home currency** setting: captures in a different currency (e.g. a USD PayPal receipt on a EUR book) show a warning badge in the review queue, since the output app receives the amount as a bare number with no currency field. Leave the setting empty to disable the badge. To support it, an input config's `match` may now name both `packages` and `senders`: the notification must then come from one of the packages **and** have a title equal to one of the senders, so only the named email sender is watched and the rest of Gmail's notifications are ignored.

## v1.3.0 - 2026-08-09
- Add a **Theme** setting with Auto (follow system), Light, and Dark modes. Every screen, dialog, and the system bars switch palettes with the choice; Auto tracks the device's dark-mode setting live.

## v1.2.0 - 2026-07-11
- Fill the expense with the captured payment's original date **and time**, even when filling hours later: the prefill intent now carries the timestamp as an epoch-millis long extra (`dateLong`, configurable via the output profile's `dateTimeMillisExtra`), which Expense Manager reads to set both the date and the time. The previously sent string `date` extra was silently ignored on its widget-add path.
- Speed up Gradle builds locally and in CI: enable the configuration cache and build cache, and make Cut Release run the tests and the signed build in a single Gradle invocation.
- Run CI tests only on pull requests: drop the push-to-master trigger, since everything reaching master goes through a tested PR or the Cut Release workflow, which runs the tests itself.

## v1.1.0 - 2026-07-03
- Fix deleting a bundled parser/output config not actually disabling it: the parser kept a hardcoded copy of every bundled config and fell back to it even when the config was hidden. Bundled assets are now the single source of defaults, so Delete/Restore in the config UI really controls what parses.
- Fix grouped-thousands amounts without decimals: `€1.234` (and `1,234`) now parse as 1234, not 1.23.
- Fix a crash when tapping Fill or Open Expense Manager with the output app not installed; the candidate is no longer marked processed for a fill that never happened.
- Make the `dropZeroAmount` global config switch actually control the zero-amount filter (it was previously always on).
- Fix the config editor corrupting regex patterns that contain a literal backslash-u sequence when a config was opened and saved.
- Ask for confirmation before "Clear local queue" deletes captured notifications.
- Dedupe captures with a SHA-256 body hash instead of a 32-bit hash, removing the (tiny) chance of two different bank SMS colliding into one key and silently dropping an expense.
- Performance: compile every parser regex once per config load instead of on each notification; re-parse stored candidates only when the parser config actually changes (and write the result back) instead of on every list read; run notification parsing/database writes and the review-queue load off the main thread; share one database connection instead of opening one per event.
- Restrict the form-filling accessibility service to the configured output app via the system-side package filter (it previously woke up for events from every app), and expire an abandoned fill after 15 minutes.
- Prune processed/skipped candidates older than 90 days so the queue and database stop growing forever; unreviewed candidates are kept indefinitely.
- Skip a parser rule whose regex does not compile instead of crashing the notification listener.
- Run the unit-test suite in CI on every push and pull request.
- Add a "Cut Release" GitHub Actions workflow: releases can now be triggered from the Actions tab (choose patch/minor/major); it bumps the version, folds the changelog, tags, runs the tests, builds the signed APK, and publishes the GitHub Release — the server-side equivalent of `scripts/release.sh --push`.

## v1.0.1 - 2026-06-30
- Fix bank SMS after the first being silently dropped: messaging apps (e.g. Textra) post every SMS from one sender under a single conversation notification, so every Bank of Cyprus SMS shared one notification key and collided on the queue's unique-key constraint after the first capture. The dedupe key now folds in the message body, so each distinct SMS is queued while re-scanning the same still-active notification still dedupes. Card-app notifications (Revolut, Google Wallet) keep a unique key per transaction, so identical charges still queue separately.
- Add a periodic listener watchdog (JobScheduler, ~15 min, persists across reboot) that re-requests a notification-listener rebind when the system has unbound it. The existing rebind only runs on an orderly disconnect; a hard process kill skipped it, leaving the listener silently dead (and payment notifications dropped) until the app was reopened or the device rebooted. When the listener is connected the job also rescans still-active notifications. Records listener connect/disconnect and watchdog activity for diagnostics.
- Date the expense to the transaction date written in the bank SMS (e.g. `ΣΤΙΣ 26/05/2026 20:17`, `on 23/05/26 12:55`, `ON 03/12/2023 13:01`) instead of when the notification arrived, so a delayed or re-posted SMS no longer lands on today. Falls back to the notification post time when the message carries no date (e.g. Revolut spends, the Eurobank card alert that only shows `@HH:mm`).

## v1.0.0 - 2026-06-23
- Start the pluggable rewrite: load parser input sources from bundled/user JSON files, include the local Cyprus bank defaults, and move watched packages/SMS senders behind that config.
- Add bundled output-target JSON for Expense Manager and use it for launch extras and accessibility view IDs.
- Add **Parser and output configs** in Settings with raw JSON edit/import/export/delete controls and a parser tester.
- Add **SMS apps** in Settings to detect or manually add SMS client packages for bank SMS parsing.
- Clarify that notification access captures new payments automatically; manual scan is only a backfill for still-active notifications.
- Add a launcher icon.
- Show the installed app version in Settings so sideloaded/debug and GitHub release builds are easy to identify.
- Scrub bundled parser examples and tests so real notification/account details are not present in the app or repository.

## v0.8.0 - 2026-06-22
- Date new expenses to when the payment notification was posted instead of the moment you fill the form, so a candidate that sits in the queue for a while still lands on the correct day (Expense Manager's `date` extra, `yyyy-MM-dd`).
- Stop queuing payment-shaped notifications that are not a completed charge: 3DS approval prompts ("waiting for your approval" / "verify a payment", the duplicate that precedes the real "successful" message), declines ("declined" / "insufficient balance"), and card verification/registration ("verified your card" / "you haven't been charged"). Also drop zero-amount captures (e.g. an €0 card registration).

## v0.7.0 - 2026-06-19
- Add a **Blocked merchants** screen (linked from Payee aliases): merchants you add there (e.g. Wolt, Bolt) are never auto-learned or auto-mapped, so a different real payee each time is not overwritten by a stale alias. Blocking a merchant also drops any alias already learned for it.
- Auto-save edits in the Payee aliases and Blocked merchants editors (on field blur and when leaving the screen) instead of requiring a per-row Save button, and fix the aliases hint to describe the stacked fields as top/below rather than left/right.

## v0.6.0 - 2026-06-17
- Improve capture reliability for bank SMS: request a battery-optimization exemption so the notification listener's process is not killed (a payment posted while the listener is dead is lost, since Android's Notification history is not readable by the app), and ask the listener to rebind if the system disconnects it.
- Move Scan, Open Expense Manager, Currency, Payee aliases, and Clear local queue into a separate **Settings** screen, leaving the main screen focused on captured candidates. Surface a "Disable battery optimization" prompt in setup and the current battery state in diagnostics.
- Replace the tap-to-delete payee aliases dialog with a full editor screen: edit both the merchant and payee of each alias, add new aliases by hand, and delete rows.

## v0.5.0 - 2026-06-15
- Default card payments to the **Credit Card** payment method: map Revolut and unrecognized sources to it, and treat raw Google Wallet card descriptors (e.g. "Revolut Visa ..0000") as Credit Card while keeping friendly card names like "Travel Card".
- Learn the payee you pick for each merchant and prefill it the next time that merchant appears, so messy notification names (e.g. "SAMPLE BILLER") map to your own saved payee. Add a **Payee aliases** screen to review and delete learned mappings.
- Leave the transaction **Status** blank instead of auto-selecting it: Expense Manager has no settable default, and auto-tapping the picker caused a duplicate Status dialog.
- Fill the transaction note into the correct description field (it previously targeted a stale view id and silently did nothing), and stop inserting the "Captured from <app>" placeholder.

## v0.4.0 - 2026-05-29
- Give recognized bank SMS that match no specific pattern sensible defaults: empty payee (instead of the sender name), the SMS text as the note, and the Electronic Transfer payment method (instead of the messaging app's name). Re-parse stored candidates on load so existing entries reflect parser improvements.
- Detect incoming bank credits (Bank of Cyprus, Eurobank Cyprus) as income: file them in Expense Manager's Income tab via the `category=Income` prefill, with payment method Electronic Transfer, capturing the payer as payee and the payment reference as a note. Handle European thousands separators (1.234,56).
- Detect Bank of Cyprus account-debit SMS (fees and transfers), tolerating Latin-lookalike Greek characters from the SMS gateway by normalizing before matching.
- Detect multi-currency Revolut notifications and let the user pick which amount to fill into Expense Manager.
- Make candidate card actions state-aware: hide **Fill Expense Manager** on skipped and processed items, and offer **Mark new** to reopen processed items.

## v0.3.0 - 2026-05-08
- Parse Google Wallet card nicknames from payment notifications and pass them to Expense Manager as the payment method.
- Publish GitHub Release APK assets with app name and version in the filename.

## v0.2.0 - 2026-05-07
- Add signed release build setup for local Gradle and GitHub Actions.
- Add a release script for version bumps, changelog updates, tags, and release pushes.
- Add Codex release workflow instructions using the local `$expense-notification-release` skill.
- Install Gradle 9.5.0 explicitly in the GitHub Actions release workflow.
- Support assignment-style Gradle version fields in the release script.
- Make the release script select the local JDK 17 path when `JAVA_HOME` is missing or invalid.
