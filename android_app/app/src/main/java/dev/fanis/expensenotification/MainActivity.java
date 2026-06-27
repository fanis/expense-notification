package dev.fanis.expensenotification;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends BaseActivity {
    private static final String APP_PACKAGE = "dev.fanis.expensenotification";
    private static final String NOTIFICATION_LISTENER = APP_PACKAGE + "/dev.fanis.expensenotification.ExpenseNotificationListener";
    private static final String ACCESSIBILITY_SERVICE = APP_PACKAGE + "/dev.fanis.expensenotification.ExpenseEntryAccessibilityService";

    private CandidateDb db;
    private LinearLayout setupActions;
    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new CandidateDb(this);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) {
            refresh();
        }
    }

    @Override
    protected boolean showSettingsInHeader() {
        return true;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        ScrollView scroll = scrollRoot(root);

        root.addView(screenTitle("Review queue"));

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(COLOR_MUTED);
        root.addView(status);

        setupActions = new LinearLayout(this);
        setupActions.setOrientation(LinearLayout.VERTICAL);
        root.addView(setupActions);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        return scroll;
    }

    private void refresh() {
        renderSetupActions();
        list.removeAllViews();
        List<Candidate> candidates = db.listAll();
        status.setText(candidates.size() + " captured notification(s). New payment notifications are captured automatically.");
        if (candidates.isEmpty()) {
            LinearLayout emptyCard = new LinearLayout(this);
            styleCard(emptyCard);
            TextView empty = bodyText("No candidates yet.");
            empty.setTextSize(16);
            emptyCard.addView(empty);
            list.addView(emptyCard);
            return;
        }
        for (Candidate candidate : candidates) {
            list.addView(card(candidate));
        }
    }

    private void renderSetupActions() {
        setupActions.removeAllViews();

        boolean notificationMissing = !isNotificationListenerEnabled();
        boolean accessibilityMissing = !isAccessibilityServiceEnabled();

        if ((notificationMissing || accessibilityMissing) && restrictedSettingsMayApply()) {
            setupActions.addView(restrictedSettingsCard());
        }

        if (notificationMissing) {
            Button notificationAccess = button("Enable notification access");
            notificationAccess.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            setupActions.addView(notificationAccess);
        }

        if (accessibilityMissing) {
            Button accessibilityAccess = button("Enable form filling");
            accessibilityAccess.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            setupActions.addView(accessibilityAccess);
        }

        if (!isIgnoringBatteryOptimizations()) {
            // Battery optimization can kill the listener process, so a payment posted
            // while it is dead is silently lost. Nudge the user to exempt the app.
            Button battery = button("Disable battery optimization (recommended)");
            battery.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
            setupActions.addView(battery);
        }
    }

    private View restrictedSettingsCard() {
        LinearLayout card = new LinearLayout(this);
        styleCard(card);
        TextView title = new TextView(this);
        title.setText("Restricted settings");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        card.addView(title);
        card.addView(bodyText(restrictedSettingsHelpText()));
        Button appInfo = secondaryButton("Open app info");
        appInfo.setOnClickListener(v -> openAppInfo());
        card.addView(appInfo);
        return card;
    }

    private View card(Candidate candidate) {
        LinearLayout card = new LinearLayout(this);
        styleCard(card);

        TextView heading = new TextView(this);
        String prefix = candidate.isIncome() ? "[Income] " : "";
        heading.setText(prefix + displayAmountLine(candidate) + "  " + candidate.merchant);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(COLOR_TEXT);
        heading.setTextSize(16);
        card.addView(heading);

        card.addView(bodyText(
                emptyDash(candidate.suggestedPaymentMethod) +
                        " - " + emptyDash(candidate.suggestedCategory)));
        card.addView(bodyText("From " + emptyDash(candidate.appName)));
        card.addView(bodyText(DateFormat.getDateTimeInstance().format(new Date(candidate.postedAt))));
        if ("SKIPPED".equals(candidate.status) || "PROCESSED".equals(candidate.status)) {
            TextView statusLabel = bodyText(statusLabel(candidate.status));
            statusLabel.setTextSize(13);
            statusLabel.setPadding(0, dp(6), 0, 0);
            card.addView(statusLabel);
        }
        if (candidate.note != null && !candidate.note.isEmpty()) {
            card.addView(bodyText(candidate.note));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if ("SKIPPED".equals(candidate.status)) {
            Button unskip = secondaryButton("Unskip");
            unskip.setOnClickListener(v -> {
                db.mark(candidate.id, "NEW");
                refresh();
            });
            addCardAction(actions, unskip);
        } else if ("PROCESSED".equals(candidate.status)) {
            Button reopen = secondaryButton("Mark new");
            reopen.setOnClickListener(v -> {
                db.mark(candidate.id, "NEW");
                refresh();
            });
            addCardAction(actions, reopen);
        } else {
            Button fill = button("Fill");
            fill.setOnClickListener(v -> fillExpenseManager(candidate));
            addCardAction(actions, fill);
            Button skip = secondaryButton("Skip");
            skip.setOnClickListener(v -> {
                db.mark(candidate.id, "SKIPPED");
                refresh();
            });
            addCardAction(actions, skip);
        }
        card.addView(actions);
        return card;
    }

    private String statusLabel(String status) {
        if ("PROCESSED".equals(status)) {
            return "Processed";
        }
        if ("SKIPPED".equals(status)) {
            return "Skipped";
        }
        return status == null ? "" : status;
    }

    private void addCardAction(LinearLayout actions, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        params.setMargins(0, dp(8), dp(8), 0);
        actions.addView(button, params);
    }

    private void fillExpenseManager(Candidate candidate) {
        String mode = CurrencyPreferences.mode(this);
        if (candidate.hasMultipleCurrencies() && CurrencyPreferences.MODE_ASK.equals(mode)) {
            promptCurrencyChoice(candidate);
            return;
        }
        String amount = candidate.hasMultipleCurrencies() && CurrencyPreferences.MODE_ORIGINAL.equals(mode)
                ? candidate.originalAmount
                : candidate.amount;
        fillExpenseManagerWithAmount(candidate, amount);
    }

    private void promptCurrencyChoice(Candidate candidate) {
        CharSequence[] options = new CharSequence[]{
                "Account: " + candidate.amountLine(),
                "Charged: " + candidate.originalAmountLine(),
        };
        new AlertDialog.Builder(this)
                .setTitle("Which amount to fill?")
                .setItems(options, (dialog, which) -> {
                    String amount = which == 1 ? candidate.originalAmount : candidate.amount;
                    fillExpenseManagerWithAmount(candidate, amount);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void fillExpenseManagerWithAmount(Candidate candidate, String amount) {
        OutputProfile profile = OutputProfile.active(this);
        // Only carry a real note (e.g. a bank SMS description); never a placeholder.
        String description = candidate.note == null ? "" : candidate.note;
        // Income transactions need category="Income" - the only value that flips
        // Expense Manager to the Income tab via the prefill intent.
        String category = candidate.isIncome() ? "Income" : "";
        // Date the expense to when the payment happened (the transaction date parsed
        // from the SMS, or the notification post time as a fallback), not now(): a
        // candidate may sit in the queue for days before the user fills it.
        String date = expenseDate(candidate.postedAt, profile.dateFormat);
        fillExpenseManager(profile, amount, candidate.merchant, candidate.suggestedPaymentMethod, description, category, date);
        db.mark(candidate.id, "PROCESSED");
        refresh();
    }

    // Expense Manager reads the "date" extra as yyyy-MM-dd (on the widgetAdd path).
    private static String expenseDate(long postedAt, String dateFormat) {
        if (postedAt <= 0) {
            return "";
        }
        try {
            return new SimpleDateFormat(dateFormat == null || dateFormat.isEmpty() ? "yyyy-MM-dd" : dateFormat, Locale.US).format(new Date(postedAt));
        } catch (IllegalArgumentException ignored) {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(postedAt));
        }
    }

    private void fillExpenseManager(OutputProfile profile, String amount, String merchant, String paymentMethod, String description, String category, String date) {
        // Prefill the learned payee for this merchant when we have one; otherwise the
        // raw merchant. We keep the raw merchant separately so the accessibility
        // service can learn merchant -> payee from whatever the user finally selects.
        String prefillPayee = PayeeAliases.resolve(this, merchant);
        getSharedPreferences("automation", MODE_PRIVATE).edit()
                .putString("target_package", profile.packageName)
                .putString("amount_id", profile.amountId)
                .putString("payee_id", profile.payeeId)
                .putString("description_id", profile.descriptionId)
                .putString("save_ids", profile.saveIdsPrefValue())
                .putString("amount", amount)
                .putString("merchant", merchant == null ? "" : merchant)
                .putString("payee", prefillPayee)
                .putString("payment_method", paymentMethod)
                .putString("description", description)
                .putString("pending_payee", "")
                .putString("state", "PENDING")
                .apply();
        Intent intent = new Intent();
        intent.setClassName(profile.packageName, profile.activity);
        for (java.util.Map.Entry<String, String> extra : profile.constantExtras.entrySet()) {
            intent.putExtra(extra.getKey(), extra.getValue());
        }
        if (amount != null && !amount.isEmpty()) {
            intent.putExtra(profile.amountExtra(), amount);
        }
        if (prefillPayee != null && !prefillPayee.isEmpty()) {
            intent.putExtra(profile.payeeExtra(), prefillPayee);
        }
        if (paymentMethod != null && !paymentMethod.isEmpty()) {
            intent.putExtra(profile.paymentMethodExtra(), paymentMethod);
        }
        if (category != null && !category.isEmpty()) {
            intent.putExtra(profile.categoryExtra(), category);
        }
        if (description != null && !description.isEmpty()) {
            intent.putExtra(profile.descriptionExtra(), description);
        }
        if (date != null && !date.isEmpty()) {
            intent.putExtra(profile.dateExtra(), date);
        }
        startActivity(intent);
    }

    private String displayAmountLine(Candidate candidate) {
        if (!candidate.hasMultipleCurrencies()) {
            return candidate.amountLine();
        }
        String mode = CurrencyPreferences.mode(this);
        if (CurrencyPreferences.MODE_ORIGINAL.equals(mode)) {
            return candidate.originalAmountLine() + " (≈ " + candidate.amountLine() + ")";
        }
        return candidate.amountLine() + " (was " + candidate.originalAmountLine() + ")";
    }

    private boolean isNotificationListenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return containsComponent(enabled, NOTIFICATION_LISTENER);
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
        return "1".equals(Settings.Secure.getString(getContentResolver(), "accessibility_enabled")) &&
                containsComponent(enabled, ACCESSIBILITY_SERVICE);
    }

    private static boolean containsComponent(String enabled, String flattenedComponent) {
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        String shortComponent = flattenedComponent.replace("/" + APP_PACKAGE + ".", "/.");
        for (String component : enabled.split(":")) {
            if (component.equals(flattenedComponent) || component.equals(shortComponent)) {
                return true;
            }
        }
        return false;
    }

}
