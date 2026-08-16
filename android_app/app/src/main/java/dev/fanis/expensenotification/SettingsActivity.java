package dev.fanis.expensenotification;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Houses the app's actions and preferences, kept off the main capture screen. */
public class SettingsActivity extends BaseActivity {

    private CandidateDb db;
    private Button currencyButton;
    private Button homeCurrencyButton;
    private Button themeButton;
    private Button payeeAliasesButton;
    private Button batteryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = CandidateDb.getInstance(this);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (batteryButton != null) {
            batteryButton.setText(batteryLabel());
        }
        if (payeeAliasesButton != null) {
            payeeAliasesButton.setText(payeeAliasesLabel());
        }
    }

    @Override
    protected boolean showBackInHeader() {
        return true;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        ScrollView scroll = scrollRoot(root);

        root.addView(screenTitle("Settings"));

        LinearLayout statusCard = new LinearLayout(this);
        styleCard(statusCard);
        statusCard.addView(summaryLine(appVersionLabel(), notificationSummary()));
        statusCard.addView(summaryLine("Form filling " + enabledWord(isAccessibilityServiceEnabled()), "Battery optimization " + (isIgnoringBatteryOptimizations() ? "off" : "on")));
        if (restrictedSettingsMayApply() && (!isNotificationListenerEnabled() || !isAccessibilityServiceEnabled())) {
            statusCard.addView(bodyText(restrictedSettingsHelpText()));
        }
        root.addView(statusCard);

        Button scan = button("Scan current notifications");
        scan.setOnClickListener(v -> scanNow());
        root.addView(scan);

        Button openExpenseManager = button("Open Expense Manager");
        openExpenseManager.setOnClickListener(v -> openExpenseManager());
        root.addView(openExpenseManager);

        currencyButton = button(currencyLabel());
        currencyButton.setOnClickListener(v -> showCurrencyDialog());
        root.addView(currencyButton);

        homeCurrencyButton = button(homeCurrencyLabel());
        homeCurrencyButton.setOnClickListener(v -> showHomeCurrencyDialog());
        root.addView(homeCurrencyButton);

        themeButton = button(themeLabel());
        themeButton.setOnClickListener(v -> showThemeDialog());
        root.addView(themeButton);

        payeeAliasesButton = button(payeeAliasesLabel());
        payeeAliasesButton.setOnClickListener(v -> startActivity(new Intent(this, PayeeAliasesActivity.class)));
        root.addView(payeeAliasesButton);

        Button configs = button("Parser and output configs");
        configs.setOnClickListener(v -> startActivity(new Intent(this, ConfigActivity.class)));
        root.addView(configs);

        Button smsApps = button("SMS apps");
        smsApps.setOnClickListener(v -> startActivity(new Intent(this, SmsAppsActivity.class)));
        root.addView(smsApps);

        batteryButton = button(batteryLabel());
        batteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        root.addView(batteryButton);

        Button clear = dangerButton("Clear local queue");
        clear.setOnClickListener(v -> confirmClearQueue());
        root.addView(clear);

        return scroll;
    }

    private void confirmClearQueue() {
        new AlertDialog.Builder(this)
                .setTitle("Clear local queue?")
                .setMessage("This permanently deletes every captured notification, including ones you have not reviewed yet.")
                .setPositiveButton("Delete all", (dialog, which) -> {
                    int deleted = db.deleteAll();
                    Toast.makeText(this, "Deleted " + deleted + " item(s).", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void scanNow() {
        int saved = ExpenseNotificationListener.scanActive(this);
        if (saved == -1) {
            Toast.makeText(this, "Enable Expense Notification Helper in notification access, then reopen this app.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } else {
            Toast.makeText(this, "Saved " + saved + " new candidate(s).", Toast.LENGTH_SHORT).show();
        }
    }

    private void openExpenseManager() {
        OutputProfile profile = OutputProfile.active(this);
        Intent intent = new Intent();
        intent.setClassName(profile.packageName, profile.activity);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open " + profile.displayName + ". Is it installed?", Toast.LENGTH_LONG).show();
        }
    }

    private String batteryLabel() {
        return isIgnoringBatteryOptimizations()
                ? "Battery optimization: off (good)"
                : "Disable battery optimization (recommended)";
    }

    private String currencyLabel() {
        return "Currency: " + CurrencyPreferences.label(CurrencyPreferences.mode(this));
    }

    private void showCurrencyDialog() {
        String[] modes = new String[]{
                CurrencyPreferences.MODE_ACCOUNT,
                CurrencyPreferences.MODE_ORIGINAL,
                CurrencyPreferences.MODE_ASK,
        };
        CharSequence[] labels = new CharSequence[modes.length];
        int currentIndex = 0;
        String current = CurrencyPreferences.mode(this);
        for (int i = 0; i < modes.length; i++) {
            labels[i] = CurrencyPreferences.label(modes[i]);
            if (modes[i].equals(current)) {
                currentIndex = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Preferred currency for multi-currency notifications")
                .setSingleChoiceItems(labels, currentIndex, (dialog, which) -> {
                    CurrencyPreferences.setMode(this, modes[which]);
                    if (currencyButton != null) {
                        currencyButton.setText(currencyLabel());
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String homeCurrencyLabel() {
        String home = CurrencyPreferences.homeCurrency(this);
        return "Home currency: " + (home.isEmpty() ? "not set" : home);
    }

    private void showHomeCurrencyDialog() {
        EditText input = new EditText(this);
        styleInput(input);
        input.setHint("3-letter code, e.g. EUR");
        input.setText(CurrencyPreferences.homeCurrency(this));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(20), dp(8), dp(20), 0);
        container.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Home currency")
                .setMessage("Captured payments in a different currency get a warning badge in the review queue, "
                        + "since the amount is filled as a bare number. Leave empty to disable.")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    String typed = input.getText().toString().trim();
                    String cleaned = CurrencyPreferences.normalizeHomeCurrency(typed);
                    if (!typed.isEmpty() && cleaned.isEmpty()) {
                        Toast.makeText(this, "Not a 3-letter currency code; home currency unchanged.", Toast.LENGTH_LONG).show();
                    } else {
                        CurrencyPreferences.setHomeCurrency(this, cleaned);
                    }
                    if (homeCurrencyButton != null) {
                        homeCurrencyButton.setText(homeCurrencyLabel());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String themeLabel() {
        return "Theme: " + ThemePreferences.label(ThemePreferences.mode(this));
    }

    private void showThemeDialog() {
        String[] modes = new String[]{
                ThemePreferences.MODE_AUTO,
                ThemePreferences.MODE_LIGHT,
                ThemePreferences.MODE_DARK,
        };
        CharSequence[] labels = new CharSequence[modes.length];
        int currentIndex = 0;
        String current = ThemePreferences.mode(this);
        for (int i = 0; i < modes.length; i++) {
            labels[i] = ThemePreferences.label(modes[i]);
            if (modes[i].equals(current)) {
                currentIndex = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(labels, currentIndex, (dialog, which) -> {
                    boolean changed = !modes[which].equals(current);
                    ThemePreferences.setMode(this, modes[which]);
                    if (themeButton != null) {
                        themeButton.setText(themeLabel());
                    }
                    dialog.dismiss();
                    if (changed) {
                        // Rebuild this screen with the new palette; stacked
                        // activities catch up in BaseActivity.onResume.
                        recreate();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String payeeAliasesLabel() {
        int blocked = PayeeAliases.blacklistCount(this);
        String label = "Payee aliases (" + PayeeAliases.count(this) + ")";
        return blocked > 0 ? label + " - " + blocked + " blocked" : label;
    }

    private String appVersionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "Version " + info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "Version unknown";
        }
    }

    private TextView summaryLine(String primary, String secondary) {
        TextView view = bodyText(primary + "\n" + secondary);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(15);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private String notificationSummary() {
        return "Notification access " + enabledWord(isNotificationListenerEnabled());
    }

    private static String enabledWord(boolean enabled) {
        return enabled ? "enabled" : "missing";
    }
}
