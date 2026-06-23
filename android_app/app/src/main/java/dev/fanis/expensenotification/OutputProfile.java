package dev.fanis.expensenotification;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class OutputProfile {
    private static OutputProfile cachedProfile;
    private static long cachedRevision = Long.MIN_VALUE;
    private static String cachedFilesDir = "";

    final String id;
    final String displayName;
    final String packageName;
    final String activity;
    final Map<String, String> constantExtras;
    final Map<String, String> fieldMap;
    final String dateFormat;
    final String amountId;
    final String payeeId;
    final String descriptionId;
    final ArrayList<String> saveIds;

    private OutputProfile(String id, String displayName, String packageName, String activity,
                          Map<String, String> constantExtras, Map<String, String> fieldMap,
                          String dateFormat, String amountId, String payeeId, String descriptionId,
                          ArrayList<String> saveIds) {
        this.id = id;
        this.displayName = displayName;
        this.packageName = packageName;
        this.activity = activity;
        this.constantExtras = constantExtras;
        this.fieldMap = fieldMap;
        this.dateFormat = dateFormat;
        this.amountId = amountId;
        this.payeeId = payeeId;
        this.descriptionId = descriptionId;
        this.saveIds = saveIds;
    }

    static OutputProfile active(Context context) {
        long revision = ConfigRevision.current(context);
        String filesDir = context == null || context.getFilesDir() == null ? "" : context.getFilesDir().getAbsolutePath();
        synchronized (OutputProfile.class) {
            if (cachedProfile != null && cachedRevision == revision && cachedFilesDir.equals(filesDir)) {
                return cachedProfile;
            }
        }
        try {
            OutputProfile profile = load(context);
            synchronized (OutputProfile.class) {
                cachedProfile = profile;
                cachedRevision = revision;
                cachedFilesDir = filesDir;
            }
            return profile;
        } catch (Exception ignored) {
            return defaults();
        }
    }

    static OutputProfile defaults() {
        LinkedHashMap<String, String> extras = new LinkedHashMap<>();
        extras.put("fromWhere", "widgetAdd");
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("amount", "amount");
        fields.put("payee", "payee");
        fields.put("paymentMethod", "paymentMethod");
        fields.put("category", "category");
        fields.put("description", "description");
        fields.put("date", "date");
        ArrayList<String> saveIds = new ArrayList<>();
        saveIds.add("com.expensemanager.pro:id/expenseSave");
        saveIds.add("com.expensemanager.pro:id/expenseSaveNew");
        return new OutputProfile(
                "expense-manager",
                "Bishinews Expense Manager",
                "com.expensemanager.pro",
                "com.expensemanager.ExpenseNewTransaction",
                extras,
                fields,
                "yyyy-MM-dd",
                "com.expensemanager.pro:id/expenseAmountInput",
                "com.expensemanager.pro:id/payee",
                "com.expensemanager.pro:id/expenseDescriptionInput",
                saveIds);
    }

    private static OutputProfile load(Context context) throws IOException, JSONException {
        LinkedHashMap<String, String> outputs = new LinkedHashMap<>();
        outputs.put("expense-manager.json", defaultJson());

        AssetManager assets = context.getAssets();
        try {
            String[] names = assets.list("outputs");
            if (names != null) {
                ArrayList<String> sorted = new ArrayList<>();
                    Collections.addAll(sorted, names);
                    Collections.sort(sorted);
                    for (String name : sorted) {
                        if (name.endsWith(".json") && !ConfigHides.isHidden(context, "outputs", name)) {
                            outputs.put(name, readAsset(assets, "outputs/" + name));
                        }
                    }
            }
        } catch (IOException ignored) {
        }

        File userOutputs = new File(context.getFilesDir(), "outputs");
        File[] files = userOutputs.listFiles();
        if (files != null) {
            ArrayList<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, files);
            Collections.sort(sorted, (a, b) -> a.getName().compareTo(b.getName()));
            for (File file : sorted) {
                if (file.isFile() && file.getName().endsWith(".json")) {
                    outputs.put(file.getName(), readFile(file));
                }
            }
        }

        String activeId = context.getSharedPreferences("config", Context.MODE_PRIVATE)
                .getString("active_output", "expense-manager");
        OutputProfile fallback = null;
        for (String json : outputs.values()) {
            OutputProfile profile = fromJson(new JSONObject(json));
            if (fallback == null) {
                fallback = profile;
            }
            if (profile.id.equals(activeId)) {
                return profile;
            }
        }
        return fallback == null ? defaults() : fallback;
    }

    static OutputProfile fromConfigJson(String json) throws JSONException {
        return fromJson(new JSONObject(json));
    }

    private static OutputProfile fromJson(JSONObject json) {
        JSONObject accessibility = json.optJSONObject("accessibility");
        ArrayList<String> saveIds = new ArrayList<>();
        JSONArray rawSaveIds = accessibility == null ? null : accessibility.optJSONArray("saveIds");
        if (rawSaveIds != null) {
            for (int i = 0; i < rawSaveIds.length(); i++) {
                saveIds.add(rawSaveIds.optString(i));
            }
        }
        return new OutputProfile(
                json.optString("id", "expense-manager"),
                json.optString("displayName", "Bishinews Expense Manager"),
                json.optString("package", "com.expensemanager.pro"),
                json.optString("activity", "com.expensemanager.ExpenseNewTransaction"),
                stringMap(json.optJSONObject("constantExtras")),
                stringMap(json.optJSONObject("fieldMap")),
                json.optString("dateFormat", "yyyy-MM-dd"),
                accessibility == null ? "" : accessibility.optString("amountId", ""),
                accessibility == null ? "" : accessibility.optString("payeeId", ""),
                accessibility == null ? "" : accessibility.optString("descriptionId", ""),
                saveIds);
    }

    private static Map<String, String> stringMap(JSONObject json) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (json == null) {
            return map;
        }
        JSONArray names = json.names();
        if (names == null) {
            return map;
        }
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            map.put(name, json.optString(name));
        }
        return map;
    }

    private String field(String canonical, String fallback) {
        String value = fieldMap.get(canonical);
        return value == null || value.isEmpty() ? fallback : value;
    }

    String amountExtra() {
        return field("amount", "amount");
    }

    String payeeExtra() {
        return field("payee", "payee");
    }

    String paymentMethodExtra() {
        return field("paymentMethod", "paymentMethod");
    }

    String categoryExtra() {
        return field("category", "category");
    }

    String descriptionExtra() {
        return field("description", "description");
    }

    String dateExtra() {
        return field("date", "date");
    }

    String saveIdsPrefValue() {
        StringBuilder builder = new StringBuilder();
        for (String saveId : saveIds) {
            if (saveId == null || saveId.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(saveId);
        }
        return builder.toString();
    }

    String summary() {
        return "Name: " + displayName + "\n"
                + "Package: " + packageName + "\n"
                + "Activity: " + activity + "\n"
                + "Amount extra: " + amountExtra() + "\n"
                + "Payee extra: " + payeeExtra() + "\n"
                + "Payment method extra: " + paymentMethodExtra() + "\n"
                + "Category extra: " + categoryExtra() + "\n"
                + "Description extra: " + descriptionExtra() + "\n"
                + "Date extra: " + dateExtra() + "\n"
                + "Accessibility save ids: " + (saveIds.isEmpty() ? "-" : saveIdsPrefValue());
    }

    private static String readAsset(AssetManager assets, String name) throws IOException {
        try (InputStream in = assets.open(name)) {
            return readAll(in);
        }
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String defaultJson() {
        return "{"
                + "\"id\":\"expense-manager\","
                + "\"displayName\":\"Bishinews Expense Manager\","
                + "\"package\":\"com.expensemanager.pro\","
                + "\"activity\":\"com.expensemanager.ExpenseNewTransaction\","
                + "\"constantExtras\":{\"fromWhere\":\"widgetAdd\"},"
                + "\"fieldMap\":{\"amount\":\"amount\",\"payee\":\"payee\",\"paymentMethod\":\"paymentMethod\","
                + "\"category\":\"category\",\"description\":\"description\",\"date\":\"date\"},"
                + "\"dateFormat\":\"yyyy-MM-dd\","
                + "\"accessibility\":{\"amountId\":\"com.expensemanager.pro:id/expenseAmountInput\","
                + "\"payeeId\":\"com.expensemanager.pro:id/payee\","
                + "\"descriptionId\":\"com.expensemanager.pro:id/expenseDescriptionInput\","
                + "\"saveIds\":[\"com.expensemanager.pro:id/expenseSave\",\"com.expensemanager.pro:id/expenseSaveNew\"]}"
                + "}";
    }
}
