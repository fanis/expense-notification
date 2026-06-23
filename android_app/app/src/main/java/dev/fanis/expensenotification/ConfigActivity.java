package dev.fanis.expensenotification;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;

public class ConfigActivity extends BaseActivity {
    private static final int REQ_IMPORT_INPUT = 10;
    private static final int REQ_IMPORT_OUTPUT = 11;
    private static final int REQ_EXPORT = 12;

    private LinearLayout root;
    private EditText testPackage;
    private EditText testApp;
    private EditText testTitle;
    private EditText testBody;
    private TextView testResult;
    private String pendingExportText;
    private String pendingExportName;

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

        root.addView(screenTitle("Configs"));

        renderTester();
        renderSection("Input parsers", "inputs", REQ_IMPORT_INPUT);
        renderSection("Output targets", "outputs", REQ_IMPORT_OUTPUT);
    }

    private void renderTester() {
        root.addView(sectionTitle("Parser tester"));
        testPackage = input("Package", "com.textra", false);
        testApp = input("App", "Textra", false);
        testTitle = input("Title or SMS sender", "", false);
        testBody = input("Body", "", true);
        LinearLayout testerCard = new LinearLayout(this);
        styleCard(testerCard);
        testerCard.addView(testPackage);
        testerCard.addView(testApp);
        testerCard.addView(testTitle);
        testerCard.addView(testBody);
        root.addView(testerCard);

        Button test = button("Test parse");
        test.setOnClickListener(v -> runParserTest());
        root.addView(test);

        testResult = bodyText("");
        testResult.setTextSize(14);
        root.addView(testResult);
    }

    private void renderSection(String label, String dirName, int importRequest) {
        root.addView(sectionTitle(label));

        Button importButton = button("Import " + singular(dirName) + " JSON");
        importButton.setOnClickListener(v -> importJson(importRequest));
        root.addView(importButton);

        for (String name : configNames(dirName)) {
            root.addView(configRow(dirName, name));
        }
        for (String name : hiddenBundledNames(dirName)) {
            root.addView(hiddenConfigRow(dirName, name));
        }
    }

    private View configRow(String dirName, String name) {
        LinearLayout row = new LinearLayout(this);
        styleCard(row);

        boolean user = userFile(dirName, name).exists();
        boolean bundled = bundledConfigExists(dirName, name);
        TextView title = new TextView(this);
        title.setText(name + (user ? " (user)" : " (bundled)"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        row.addView(title);

        ArrayList<Button> actions = new ArrayList<>();

        Button edit = secondaryButton("Edit");
        edit.setOnClickListener(v -> editConfig(dirName, name));
        actions.add(edit);

        Button export = secondaryButton("Export");
        export.setOnClickListener(v -> exportConfig(dirName, name));
        actions.add(export);

        Button validate = secondaryButton("Validate");
        validate.setOnClickListener(v -> validateConfig(dirName, name));
        actions.add(validate);

        if ("inputs".equals(dirName)) {
            Button tests = secondaryButton("Run tests");
            tests.setOnClickListener(v -> runEmbeddedTests(name));
            actions.add(tests);
        }

        if ("outputs".equals(dirName)) {
            Button use = secondaryButton("Use");
            use.setOnClickListener(v -> useOutput(name));
            actions.add(use);

            Button preview = secondaryButton("Preview");
            preview.setOnClickListener(v -> previewOutput(name));
            actions.add(preview);
        }

        if (user || bundled) {
            Button delete = secondaryButton("Delete");
            delete.setOnClickListener(v -> confirmDelete(dirName, name));
            actions.add(delete);
        }

        addActionRows(row, actions);
        return row;
    }

    private View hiddenConfigRow(String dirName, String name) {
        LinearLayout row = new LinearLayout(this);
        styleCard(row);

        TextView title = new TextView(this);
        title.setText(name + " (hidden bundled)");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        row.addView(title);

        ArrayList<Button> actions = new ArrayList<>();
        Button restore = secondaryButton("Restore");
        restore.setOnClickListener(v -> restoreBundled(dirName, name));
        actions.add(restore);

        Button export = secondaryButton("Export");
        export.setOnClickListener(v -> exportConfig(dirName, name));
        actions.add(export);

        addActionRows(row, actions);
        return row;
    }

    private void addActionRows(LinearLayout parent, ArrayList<Button> buttons) {
        LinearLayout current = null;
        for (int i = 0; i < buttons.size(); i++) {
            if (i % 3 == 0) {
                current = new LinearLayout(this);
                current.setOrientation(LinearLayout.HORIZONTAL);
                parent.addView(current);
            }
            Button button = buttons.get(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f);
            params.setMargins(0, dp(4), dp(6), 0);
            current.addView(button, params);
        }
    }

    private void runParserTest() {
        ExpenseParser.ParseDiagnostics diagnostics = ExpenseParser.debug(
                this,
                text(testPackage),
                text(testApp),
                "test",
                System.currentTimeMillis(),
                text(testTitle),
                text(testBody));
        Candidate c = diagnostics.candidate;
        if (c == null) {
            testResult.setText("No candidate matched.\n\n" + join(diagnostics.steps));
            return;
        }
        testResult.setText(
                "Matched candidate\n"
                        + "Source: " + emptyDash(diagnostics.matchedSource) + "\n"
                        + "Rule: " + emptyDash(diagnostics.matchedRule) + "\n"
                        + "Type: " + c.transactionType + "\n"
                        + "Amount: " + c.amountLine() + "\n"
                        + (c.hasMultipleCurrencies() ? "Original: " + c.originalAmountLine() + "\n" : "")
                        + "Merchant: " + emptyDash(c.merchant) + "\n"
                        + "Method: " + emptyDash(c.suggestedPaymentMethod) + "\n"
                        + "Category: " + emptyDash(c.suggestedCategory) + "\n"
                        + "Note: " + emptyDash(c.note));
    }

    private void editConfig(String dirName, String name) {
        EditText editor = input(name, readConfig(dirName, name), true);
        editor.setMinLines(14);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setView(editor)
                .setPositiveButton("Save", (dialog, which) -> saveConfig(dirName, name, text(editor)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveConfig(String dirName, String name, String json) {
        try {
            ConfigValidator.Result validation = ConfigValidator.validate(dirName, cleanJsonName(name), json);
            if (!validation.ok()) {
                showMessage("Config validation failed", validation.summary());
                return;
            }
            File dir = new File(getFilesDir(), dirName);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Could not create " + dirName);
            }
            try (FileOutputStream out = new FileOutputStream(new File(dir, cleanJsonName(name)))) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            ConfigHides.restore(this, dirName, name);
            ConfigRevision.bump(this);
            Toast.makeText(this, "Saved " + name, Toast.LENGTH_SHORT).show();
            render();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save config: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(String dirName, String name) {
        boolean user = userFile(dirName, name).exists();
        boolean bundled = bundledConfigExists(dirName, name);
        if ("outputs".equals(dirName) && outputCountAfterDelete(name, user, bundled) < 1) {
            showMessage("Cannot delete output", "You need at least one output target. Restore or import another output target before deleting this one.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + name + "?")
                .setMessage(user
                        ? "This removes the user override."
                        : "This hides the bundled default. You can restore it later.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    boolean deleted = user
                            ? userFile(dirName, name).delete()
                            : bundled && ConfigHides.hide(this, dirName, name);
                    ConfigRevision.bump(this);
                    Toast.makeText(this, deleted ? "Deleted " + name : "Nothing deleted", Toast.LENGTH_SHORT).show();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restoreBundled(String dirName, String name) {
        boolean restored = ConfigHides.restore(this, dirName, name);
        ConfigRevision.bump(this);
        Toast.makeText(this, restored ? "Restored " + name : "Nothing restored", Toast.LENGTH_SHORT).show();
        render();
    }

    private void importJson(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, requestCode);
    }

    private void exportConfig(String dirName, String name) {
        pendingExportName = cleanJsonName(name);
        pendingExportText = readConfig(dirName, name);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, pendingExportName);
        startActivityForResult(intent, REQ_EXPORT);
    }

    private void useOutput(String name) {
        try {
            String id = new JSONObject(readConfig("outputs", name)).optString("id", "");
            if (id.isEmpty()) {
                throw new IllegalArgumentException("missing id");
            }
            getSharedPreferences("config", MODE_PRIVATE).edit()
                    .putString("active_output", id)
                    .apply();
            ConfigRevision.bump(this);
            Toast.makeText(this, "Using output " + id, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not use output: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void validateConfig(String dirName, String name) {
        ConfigValidator.Result result = ConfigValidator.validate(dirName, name, readConfig(dirName, name));
        showMessage(result.ok() ? "Config is valid" : "Config validation failed", result.summary());
    }

    private void runEmbeddedTests(String name) {
        ConfigSelfTest.Result result = ConfigSelfTest.runInputConfig(name, readConfig("inputs", name));
        showMessage(result.passed() ? "Parser tests passed" : "Parser tests failed", result.summary());
    }

    private void previewOutput(String name) {
        try {
            OutputProfile profile = OutputProfile.fromConfigJson(readConfig("outputs", name));
            showMessage("Output preview", profile.summary());
        } catch (Exception e) {
            showMessage("Output preview failed", e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_IMPORT_INPUT || requestCode == REQ_IMPORT_OUTPUT) {
                String json = readUri(uri);
                String dirName = requestCode == REQ_IMPORT_INPUT ? "inputs" : "outputs";
                String name = cleanJsonName(displayName(uri));
                saveConfig(dirName, name, json);
            } else if (requestCode == REQ_EXPORT) {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        out.write((pendingExportText == null ? "" : pendingExportText).getBytes(StandardCharsets.UTF_8));
                    }
                }
                Toast.makeText(this, "Exported " + pendingExportName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Config action failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private ArrayList<String> configNames(String dirName) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            String[] bundled = getAssets().list(dirName);
            if (bundled != null) {
                ArrayList<String> sorted = new ArrayList<>();
                Collections.addAll(sorted, bundled);
                Collections.sort(sorted);
                for (String name : sorted) {
                    if (!ConfigHides.isHidden(this, dirName, name)) {
                        names.add(name);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        File dir = new File(getFilesDir(), dirName);
        File[] files = dir.listFiles();
        if (files != null) {
            ArrayList<String> sorted = new ArrayList<>();
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".json")) {
                    sorted.add(file.getName());
                }
            }
            Collections.sort(sorted);
            names.addAll(sorted);
        }
        ArrayList<String> result = new ArrayList<>();
        for (String name : names) {
            if (name.endsWith(".json")) {
                result.add(name);
            }
        }
        return result;
    }

    private ArrayList<String> hiddenBundledNames(String dirName) {
        ArrayList<String> result = new ArrayList<>();
        try {
            String[] bundled = getAssets().list(dirName);
            if (bundled != null) {
                for (String name : bundled) {
                    if (name.endsWith(".json") && ConfigHides.isHidden(this, dirName, name)) {
                        result.add(name);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        Collections.sort(result);
        return result;
    }

    private String readConfig(String dirName, String name) {
        File user = userFile(dirName, name);
        try {
            if (user.exists()) {
                return decodeUnicodeEscapes(readFile(user));
            }
            return decodeUnicodeEscapes(readAsset(dirName + "/" + name));
        } catch (IOException e) {
            return "{}";
        }
    }

    private File userFile(String dirName, String name) {
        return new File(new File(getFilesDir(), dirName), cleanJsonName(name));
    }

    private boolean bundledConfigExists(String dirName, String name) {
        try {
            getAssets().open(dirName + "/" + cleanJsonName(name)).close();
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private int outputCountAfterDelete(String name, boolean user, boolean bundled) {
        ArrayList<String> outputs = configNames("outputs");
        String cleanName = cleanJsonName(name);
        if (user) {
            if (!bundled || ConfigHides.isHidden(this, "outputs", cleanName)) {
                outputs.remove(cleanName);
            }
        } else if (bundled) {
            outputs.remove(cleanName);
        }
        return outputs.size();
    }

    private EditText input(String hint, String value, boolean multiline) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(!multiline);
        input.setInputType(multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_TEXT);
        styleInput(input);
        return input;
    }

    private String text(EditText input) {
        return input == null || input.getText() == null ? "" : input.getText().toString();
    }

    private String cleanJsonName(String name) {
        String cleaned = name == null ? "config.json" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
        if (cleaned.isEmpty()) {
            cleaned = "config.json";
        }
        return cleaned.endsWith(".json") ? cleaned : cleaned + ".json";
    }

    private String singular(String dirName) {
        return "inputs".equals(dirName) ? "input" : "output";
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null || message.isEmpty() ? "-" : message)
                .setPositiveButton("OK", null)
                .show();
    }

    private String join(java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        }
        return "config.json";
    }

    private String readUri(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return in == null ? "" : readAll(in);
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            return readAll(in);
        }
    }

    private String readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private String decodeUnicodeEscapes(String text) {
        if (text == null || text.indexOf("\\u") < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (i + 5 < text.length() && text.charAt(i) == '\\' && text.charAt(i + 1) == 'u') {
                String hex = text.substring(i + 2, i + 6);
                try {
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            out.append(text.charAt(i));
        }
        return out.toString();
    }
}
