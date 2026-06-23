# Changelog

## Unreleased

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
