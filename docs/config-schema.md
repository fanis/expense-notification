# Parser and Output Config Schema

Configs are JSON files bundled in `android_app/app/src/main/assets` and user-editable on-device from Settings > Parser and output configs. User files with the same name override bundled files. Deleting a bundled config from the UI hides it: the parser stops using it entirely until it is restored.

## Global Config

`global.json` holds parser-wide settings:

- `smsPackages`: messaging app packages whose notifications are checked against SMS `senders`. Extended on-device from Settings > SMS apps.
- `rejectPhrases`: lowercase phrases that mark a notification as not-a-charge (3DS prompts, declines, card verification); matching notifications are never queued.
- `dropZeroAmount`: drop zero-amount captures (e.g. a €0 card registration). Defaults to `true`.

## Input Parser

Input configs live under `inputs/`.

Required fields:

- `id`: stable parser id.
- `displayName`: shown in diagnostics.
- `enabled`: boolean.
- `priority`: lower numbers run first.
- `match`: `packages` for app notifications, `senders` for SMS sender names.
- `rules`: ordered parser rules.

Optional fields:

- `transforms`: `foldGreek`, `normalizeAmount`, `multiCurrency`.
- `tests`: embedded parser samples runnable from the config UI and unit tests.

Rule fields:

- `name`: diagnostic name.
- `type`: `regex` or `amountFallback`.
- `pattern`: Java regex for `regex` rules.
- `flags`: `caseInsensitive`, `unicodeCase`, `multiline`, `dotall`.
- `merchantSource`: `title`, `empty`, `firstNonNoiseLine`, or omitted.
- `noteSource`: `body` or omitted.
- `output`: maps extracted values to Expense Manager defaults.

Regex rules must define `(?<amount>...)`. Optional named groups are `currency`, `merchant`, `note`, and `card`.

Output templates:

- `currency`: fixed currency or `$currency` to read a group.
- `paymentMethod`: fixed text, `@packageDefault`, `@walletCard`, or text containing `${card}`.
- `category`: fixed text, `@keyword`, or `@accountDebitKeyword`.
- `type`: `EXPENSE` or `INCOME`.

Embedded tests:

```json
{
  "name": "completed spend",
  "package": "com.example.bank",
  "app": "Example Bank",
  "title": "Example Bank",
  "body": "Paid EUR 12.40 at SAMPLE MARKET",
  "expect": {
    "amount": "12.40",
    "currency": "EUR",
    "merchant": "SAMPLE MARKET",
    "category": "Groceries",
    "paymentMethod": "Example Bank",
    "type": "EXPENSE"
  }
}
```

Set `"matched": false` in `expect` for rejection samples.

## Output Profile

Output configs live under `outputs/`.

Required fields:

- `id`: stable output id.
- `displayName`: shown in the config UI.
- `package`: target app package.
- `activity`: target activity class.

Optional fields:

- `constantExtras`: extras always sent to the target activity.
- `fieldMap`: maps canonical fields to target extra names.
- `dateFormat`: Java date format, default `yyyy-MM-dd`.
- `timeFormat`: Java time format for the time-of-day extra, default `HH:mm`.
- `accessibility`: view ids used by the save automation.

Canonical `fieldMap` keys are `amount`, `payee`, `paymentMethod`, `category`, `description`, `date`, and `time`. The `date` and `time` extras are formatted from the captured payment's timestamp (the SMS transaction date, or the notification post time), so filling an expense hours later still carries the original date and time; whether the target app applies the time extra depends on its intent interface.

Accessibility fields:

- `amountId`
- `payeeId`
- `descriptionId`
- `saveIds`

## Language Handling

Regex can match Unicode text directly. Use JSON `\uXXXX` escapes if an editor or transport mangles non-ASCII text. Add language-specific transforms only when the notification transport changes the characters before the regex sees them, such as Greek capital lookalikes being mixed with Latin letters.
