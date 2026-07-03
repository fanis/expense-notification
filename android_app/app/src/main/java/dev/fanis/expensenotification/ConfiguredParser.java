package dev.fanis.expensenotification;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The runtime parser: global config plus the enabled input sources, in priority
 * order. The bundled asset configs are the single source of truth for defaults;
 * user files in filesDir/inputs override assets of the same name, and hidden
 * bundled configs are excluded entirely. Instances are immutable and cached per
 * config revision, so rebuilding only happens after a config change.
 */
final class ConfiguredParser {
    private static ConfiguredParser cachedContextParser;
    private static long cachedRevision = Long.MIN_VALUE;
    private static String cachedFilesDir = "";

    private final GlobalConfig global;
    private final List<InputSource> sources;

    private ConfiguredParser(GlobalConfig global, List<InputSource> sources) {
        this.global = global;
        this.sources = sources;
    }

    /** Last-resort parser with no input sources; only used when a context is missing or loading fails. */
    static ConfiguredParser defaults() {
        return new ConfiguredParser(GlobalConfig.defaults(), new ArrayList<>());
    }

    static ConfiguredParser forContext(Context context) {
        if (context == null) {
            return defaults();
        }
        Context appContext = context.getApplicationContext();
        long revision = ConfigRevision.current(appContext);
        String filesDir = appContext.getFilesDir() == null ? "" : appContext.getFilesDir().getAbsolutePath();
        synchronized (ConfiguredParser.class) {
            if (cachedContextParser != null && cachedRevision == revision && cachedFilesDir.equals(filesDir)) {
                return cachedContextParser;
            }
        }
        try {
            ConfiguredParser parser = fromContext(appContext);
            synchronized (ConfiguredParser.class) {
                cachedContextParser = parser;
                cachedRevision = revision;
                cachedFilesDir = filesDir;
            }
            return parser;
        } catch (Exception ignored) {
            return defaults();
        }
    }

    boolean isWatched(String packageName, String title) {
        for (InputSource source : sources) {
            if (source.enabled && source.matches(packageName, title, global.smsPackages)) {
                return true;
            }
        }
        return false;
    }

    Candidate parse(String packageName, String appName, String key, long postedAt, String title, String body) {
        NotificationText text = combinedText(title, body);
        if (isRejected(text.combined)) {
            return null;
        }
        for (InputSource source : sources) {
            if (!source.enabled || !source.matches(packageName, title, global.smsPackages)) {
                continue;
            }
            for (InputRule rule : source.rules) {
                Candidate candidate = rule.parse(global, packageName, appName, key, postedAt, title, body, text);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    ExpenseParser.ParseDiagnostics debug(String packageName, String appName, String key, long postedAt,
                                         String title, String body) {
        ArrayList<String> steps = new ArrayList<>();
        NotificationText text = combinedText(title, body);
        if (isRejected(text.combined)) {
            steps.add("Rejected by global reject phrase.");
            return new ExpenseParser.ParseDiagnostics(null, "", "", steps);
        }
        for (InputSource source : sources) {
            String sourceName = source.displayName + " (" + source.id + ")";
            if (!source.enabled) {
                steps.add("Skipped disabled source: " + sourceName);
                continue;
            }
            if (!source.matches(packageName, title, global.smsPackages)) {
                steps.add("Source did not match app/sender: " + sourceName);
                continue;
            }
            steps.add("Source matched app/sender: " + sourceName);
            for (InputRule rule : source.rules) {
                Candidate candidate = rule.parse(global, packageName, appName, key, postedAt, title, body, text);
                if (candidate != null) {
                    steps.add("Rule matched: " + rule.name + " (" + rule.type + ")");
                    return new ExpenseParser.ParseDiagnostics(candidate, source.displayName, rule.name, steps);
                }
                steps.add("Rule did not match: " + rule.name + " (" + rule.type + ")");
            }
        }
        return new ExpenseParser.ParseDiagnostics(null, "", "", steps);
    }

    private static NotificationText combinedText(String title, String body) {
        return new NotificationText((ExpenseParser.safe(title) + "\n" + ExpenseParser.safe(body)).trim());
    }

    private boolean isRejected(String combined) {
        String lower = combined.toLowerCase(Locale.ROOT);
        for (String phrase : global.rejectPhrases) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static ConfiguredParser fromContext(Context context) throws JSONException {
        AssetManager assets = context.getAssets();
        GlobalConfig global = GlobalConfig.defaults();
        try {
            String assetGlobal = IoUtil.readAsset(assets, "global.json");
            if (!assetGlobal.isEmpty()) {
                global = GlobalConfig.fromJson(new JSONObject(assetGlobal));
            }
        } catch (IOException ignored) {
        }

        LinkedHashMap<String, String> inputs = new LinkedHashMap<>();
        try {
            String[] names = assets.list("inputs");
            if (names != null) {
                ArrayList<String> sorted = new ArrayList<>();
                Collections.addAll(sorted, names);
                Collections.sort(sorted);
                for (String name : sorted) {
                    if (name.endsWith(".json") && !ConfigHides.isHidden(context, "inputs", name)) {
                        try {
                            inputs.put(name, IoUtil.readAsset(assets, "inputs/" + name));
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }

        File userInputs = new File(context.getFilesDir(), "inputs");
        File[] files = userInputs.listFiles();
        if (files != null) {
            ArrayList<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, files);
            Collections.sort(sorted, Comparator.comparing(File::getName));
            for (File file : sorted) {
                if (file.isFile() && file.getName().endsWith(".json")) {
                    try {
                        inputs.put(file.getName(), IoUtil.readFile(file));
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return fromJson(global.withSmsPackages(SmsApps.confirmedPackages(context)), inputs);
    }

    static ConfiguredParser fromJson(String globalJson, LinkedHashMap<String, String> inputJsonByFile)
            throws JSONException {
        return fromJson(GlobalConfig.fromJson(new JSONObject(globalJson)), inputJsonByFile);
    }

    static ConfiguredParser fromJson(GlobalConfig global, LinkedHashMap<String, String> inputJsonByFile)
            throws JSONException {
        ArrayList<InputSource> sources = new ArrayList<>();
        for (Map.Entry<String, String> entry : inputJsonByFile.entrySet()) {
            sources.add(InputSource.fromJson(entry.getKey(), new JSONObject(entry.getValue())));
        }
        Collections.sort(sources, Comparator.comparingInt(source -> source.priority));
        return new ConfiguredParser(global, sources);
    }
}
