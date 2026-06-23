package dev.fanis.expensenotification;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Houses the app's actions and preferences, kept off the main capture screen. */
public class SettingsActivity extends BaseActivity {

    private CandidateDb db;
    private Button currencyButton;
    private Button payeeAliasesButton;
    private Button batteryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new CandidateDb(this);
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
        clear.setOnClickListener(v -> {
            int deleted = db.deleteAll();
            Toast.makeText(this, "Deleted " + deleted + " item(s).", Toast.LENGTH_SHORT).show();
        });
        root.addView(clear);

        return scroll;
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
        Intent intent = new Intent();
        intent.setClassName("com.expensemanager.pro", "com.expensemanager.ExpenseNewTransaction");
        startActivity(intent);
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

    private boolean isNotificationListenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        String component = getPackageName() + "/" + getPackageName() + ".ExpenseNotificationListener";
        String shortComponent = getPackageName() + "/.ExpenseNotificationListener";
        return enabled != null && (enabled.contains(component) || enabled.contains(shortComponent));
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
        String component = getPackageName() + "/" + getPackageName() + ".ExpenseEntryAccessibilityService";
        String shortComponent = getPackageName() + "/.ExpenseEntryAccessibilityService";
        return "1".equals(Settings.Secure.getString(getContentResolver(), "accessibility_enabled")) &&
                enabled != null && (enabled.contains(component) || enabled.contains(shortComponent));
    }

    private static String enabledWord(boolean enabled) {
        return enabled ? "enabled" : "missing";
    }
}
