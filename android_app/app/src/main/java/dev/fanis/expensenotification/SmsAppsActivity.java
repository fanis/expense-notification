package dev.fanis.expensenotification;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SmsAppsActivity extends BaseActivity {
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        root = new LinearLayout(this);
        ScrollView scroll = scrollRoot(root);
        render();
        return scroll;
    }

    @Override
    protected boolean showBackInHeader() {
        return true;
    }

    private void render() {
        root.removeAllViews();

        root.addView(screenTitle("SMS apps"));

        TextView help = bodyText("Bank SMS matching uses the SMS app package plus the bank sender title. Add the SMS app you use if it is not already listed.");
        root.addView(help);

        Button detect = button("Detect SMS apps");
        detect.setOnClickListener(v -> showPicker("Detected SMS apps", SmsApps.detectedSmsApps(this)));
        root.addView(detect);

        Button addInstalled = button("Add installed app");
        addInstalled.setOnClickListener(v -> showPicker("Installed apps", SmsApps.launchableApps(this)));
        root.addView(addInstalled);

        renderConfigured();
    }

    private void renderConfigured() {
        TextView configured = sectionTitle("Active SMS packages");
        root.addView(configured);

        Set<String> bundled = ExpenseParser.defaultSmsPackages();
        Set<String> custom = SmsApps.confirmedPackages(this);
        ArrayList<String> active = new ArrayList<>();
        active.addAll(SmsApps.configuredPackages(this, bundled));
        Collections.sort(active);

        if (active.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No SMS packages configured.");
            empty.setTextColor(COLOR_MUTED);
            root.addView(empty);
            return;
        }

        for (String packageName : active) {
            LinearLayout row = new LinearLayout(this);
            styleCard(row);

            String origin = custom.contains(packageName) ? "custom" : "bundled";
            TextView text = bodyText(packageName + " (" + origin + ")");
            text.setTextColor(COLOR_TEXT);
            row.addView(text);

            if (custom.contains(packageName)) {
                Button remove = dangerButton("Remove");
                remove.setOnClickListener(v -> {
                    SmsApps.removePackage(this, packageName);
                    Toast.makeText(this, "Removed " + packageName, Toast.LENGTH_SHORT).show();
                    render();
                });
                row.addView(remove);
            }
            root.addView(row);
        }
    }

    private void showPicker(String title, ArrayList<SmsApps.AppChoice> apps) {
        if (apps.isEmpty()) {
            Toast.makeText(this, "No apps found.", Toast.LENGTH_SHORT).show();
            return;
        }

        Set<String> active = SmsApps.configuredPackages(this, ExpenseParser.defaultSmsPackages());
        ArrayList<SmsApps.AppChoice> candidates = new ArrayList<>();
        for (SmsApps.AppChoice app : apps) {
            if (!active.contains(app.packageName)) {
                candidates.add(app);
            }
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "All detected apps are already configured.", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence[] labels = new CharSequence[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            labels[i] = candidates.get(i).display();
        }
        HashSet<Integer> selected = new HashSet<>();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(labels, null, (dialog, which, checked) -> {
                    if (checked) {
                        selected.add(which);
                    } else {
                        selected.remove(which);
                    }
                })
                .setPositiveButton("Add", (dialog, which) -> {
                    int added = 0;
                    for (Integer index : selected) {
                        SmsApps.addPackage(this, candidates.get(index).packageName);
                        added++;
                    }
                    Toast.makeText(this, "Added " + added + " SMS app(s).", Toast.LENGTH_SHORT).show();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

}
