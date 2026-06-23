# Parser and Output Config Schema

Configs are JSON files bundled in `android_app/app/src/main/assets` and user-editable on-device from Settings > Parser and output configs. User files with the same name override bundled files.

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
- `accessibility`: view ids used by the save automation.

Canonical `fieldMap` keys are `amount`, `payee`, `paymentMethod`, `category`, `description`, and `date`.

Accessibility fields:

- `amountId`
- `payeeId`
- `descriptionId`
- `saveIds`

## Language Handling

Regex can match Unicode text directly. Use JSON `\uXXXX` escapes if an editor or transport mangles non-ASCII text. Add language-specific transforms only when the notification transport changes the characters before the regex sees them, such as Greek capital lookalikes being mixed with Latin letters.
