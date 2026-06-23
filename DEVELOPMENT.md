# Development Notes

## Notification Capture

`ExpenseNotificationListener` asks `ExpenseParser` whether a notification is watched. The watch list comes from JSON input-source configs in `assets/inputs/*.json`, overlaid by user files in `filesDir/inputs/` by filename. SMS package names and global rejection phrases come from `assets/global.json`.

Users can add SMS client packages from **Settings > SMS apps**. Confirmed packages are stored in the `sms_apps` preferences and merged with `global.smsPackages` at parser load time, so supporting a new SMS client does not require editing bundled JSON.

Parser and output config loads are cached by app data directory plus a config revision. Saving/deleting/importing configs, switching output profiles, or changing confirmed SMS apps bumps the revision so runtime parsing and filling pick up the change.

The listener stores parsed candidates in a local SQLite database via `CandidateDb`.

## Parsing

`ExpenseParser` loads the active input configs, rejects non-charge notifications through global data, and then applies source rules. The default sources still cover common amount formats such as:

```text
€2.75
2.75 EUR
EUR 2.75
Paid EUR 2.75 at Wolt
```

Bundled defaults include local bank rules:

```text
Eurobank: Greek card approval SMS, extracting card last 4, merchant, EUR amount.
Bank of Cyprus: Greek "BOC Message" card-use SMS, extracting card last 4, merchant, EUR amount.
Google Wallet: extracts the card nickname from notification text like "€40.00 with Travel Card" and uses it as the payment method.
```

The Bank of Cyprus and Eurobank defaults are JSON regex rules with `foldGreek` and `normalizeAmount` transforms. The config engine also supports generic amount fallback rules for added sources. Merchant/category rules are intentionally simple. Expense Manager remains responsible for final payee/category behavior when a saved payee is selected.

Each bundled input config carries a `tests` array with sample notifications. `ConfigSelfTest` runs those samples, and `ConfigValidator` checks JSON structure, regex compilation, known flags/transforms, required `amount` groups, output profile fields, and embedded tests. The same validation is used from the config UI and unit tests.

## Expense Manager Filling

Output targets are loaded from `assets/outputs/*.json`, overlaid by user files in `filesDir/outputs/`. The active output id is stored in the `config` preferences under `active_output`.

`ExpenseEntryAccessibilityService` opens/fills the active output target's new transaction screen. The bundled target is Expense Manager.

Known stable Expense Manager IDs:

```text
com.expensemanager.pro:id/expenseAmountInput
com.expensemanager.pro:id/payee
com.expensemanager.pro:id/ok
```

The filler passes a captured payment method through Expense Manager's `paymentMethod` intent extra, then selects saved payees by typing a prefix into Expense Manager's payee field and clicking the exact autocomplete match when available. This is required because Expense Manager appears to run category mapping from the autocomplete selection event, not from raw text assignment.

## Testing With Synthetic Notifications

`adb shell cmd notification post` posts as `com.android.shell`, not as Revolut or Wallet. The production app intentionally does not watch `com.android.shell`.

For a local test, temporarily add `com.android.shell` to the watched packages, post:

```powershell
adb shell "cmd notification post -t Revolut expense_capture_test 'Paid EUR 9.87 at Test Market'"
```

Then remove the shell test source before reinstalling a normal build.
