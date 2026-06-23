package dev.fanis.expensenotification;

import android.content.Context;

import java.io.File;

final class ConfigHides {
    private ConfigHides() {
    }

    static boolean isHidden(Context context, String dirName, String name) {
        return marker(context, dirName, name).exists();
    }

    static boolean hide(Context context, String dirName, String name) {
        try {
            File file = marker(context, dirName, name);
            File parent = file.getParentFile();
            return (parent == null || parent.exists() || parent.mkdirs()) && (file.exists() || file.createNewFile());
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean restore(Context context, String dirName, String name) {
        File file = marker(context, dirName, name);
        return !file.exists() || file.delete();
    }

    private static File marker(Context context, String dirName, String name) {
        return new File(new File(context.getFilesDir(), dirName), clean(name) + ".hidden");
    }

    private static String clean(String name) {
        String cleaned = name == null ? "config.json" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
        if (cleaned.isEmpty()) {
            cleaned = "config.json";
        }
        return cleaned.endsWith(".json") ? cleaned : cleaned + ".json";
    }
}
