package dev.fanis.expensenotification;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class SmsApps {
    private static final String PREFS = "sms_apps";
    private static final String KEY_CONFIRMED = "confirmed_packages";

    private SmsApps() {
    }

    static Set<String> confirmedPackages(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_CONFIRMED, Collections.emptySet()));
    }

    static Set<String> configuredPackages(Context context, Set<String> bundledPackages) {
        HashSet<String> packages = new HashSet<>(bundledPackages);
        packages.addAll(confirmedPackages(context));
        return packages;
    }

    static void addPackage(Context context, String packageName) {
        String cleaned = clean(packageName);
        if (cleaned.isEmpty()) {
            return;
        }
        HashSet<String> packages = new HashSet<>(confirmedPackages(context));
        packages.add(cleaned);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_CONFIRMED, packages)
                .apply();
        ConfigRevision.bump(context);
    }

    static void removePackage(Context context, String packageName) {
        HashSet<String> packages = new HashSet<>(confirmedPackages(context));
        packages.remove(clean(packageName));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_CONFIRMED, packages)
                .apply();
        ConfigRevision.bump(context);
    }

    static ArrayList<AppChoice> detectedSmsApps(Context context) {
        LinkedHashMap<String, AppChoice> apps = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();

        String defaultPackage = Telephony.Sms.getDefaultSmsPackage(context);
        addPackage(pm, apps, defaultPackage);

        query(pm, apps, new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")));
        query(pm, apps, new Intent(Intent.ACTION_SENDTO, Uri.parse("sms:")));
        query(pm, apps, new Intent(Intent.ACTION_VIEW, Uri.parse("sms:")));

        ArrayList<AppChoice> result = new ArrayList<>(apps.values());
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    static ArrayList<AppChoice> launchableApps(Context context) {
        LinkedHashMap<String, AppChoice> apps = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
            if (info.activityInfo != null) {
                addPackage(pm, apps, info.activityInfo.packageName);
            }
        }
        ArrayList<AppChoice> result = new ArrayList<>(apps.values());
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    private static void query(PackageManager pm, Map<String, AppChoice> apps, Intent intent) {
        for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
            if (info.activityInfo != null) {
                addPackage(pm, apps, info.activityInfo.packageName);
            }
        }
    }

    private static void addPackage(PackageManager pm, Map<String, AppChoice> apps, String packageName) {
        String cleaned = clean(packageName);
        if (cleaned.isEmpty() || apps.containsKey(cleaned)) {
            return;
        }
        String label = cleaned;
        try {
            label = pm.getApplicationLabel(pm.getApplicationInfo(cleaned, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        apps.put(cleaned, new AppChoice(label, cleaned));
    }

    private static String clean(String packageName) {
        return packageName == null ? "" : packageName.trim();
    }

    static final class AppChoice {
        final String label;
        final String packageName;

        AppChoice(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }

        String display() {
            return label + "\n" + packageName;
        }
    }
}
