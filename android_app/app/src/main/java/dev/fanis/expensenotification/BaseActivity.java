package dev.fanis.expensenotification;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared UI scaffolding and battery-optimization helpers for the app's screens. */
abstract class BaseActivity extends Activity {
    protected static final int COLOR_TEAL = 0xff13696a;
    protected static final int COLOR_BG = 0xfff6f8f9;
    protected static final int COLOR_CARD = 0xffffffff;
    protected static final int COLOR_BORDER = 0xffd6dde2;
    protected static final int COLOR_TEXT = 0xff0d1924;
    protected static final int COLOR_MUTED = 0xff485460;
    protected static final int COLOR_DANGER = 0xff9f2f44;

    /** Wraps a vertical content column in a scroller with status/navigation-bar insets applied. */
    protected ScrollView scrollRoot(LinearLayout root) {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(COLOR_TEAL);
            window.setNavigationBarColor(Color.WHITE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(COLOR_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_TEAL);

        if (showBackInHeader()) {
            ImageButton back = new ImageButton(this);
            back.setImageResource(R.drawable.ic_arrow_back);
            back.setContentDescription("Back");
            back.setBackgroundColor(Color.TRANSPARENT);
            back.setColorFilter(Color.WHITE);
            back.setPadding(dp(10), dp(10), dp(10), dp(10));
            back.setScaleType(ImageButton.ScaleType.CENTER);
            back.setMinimumWidth(dp(52));
            back.setMinimumHeight(dp(52));
            back.setOnClickListener(v -> openHeaderBack());
            header.addView(back, new LinearLayout.LayoutParams(
                    dp(52),
                    dp(52)));
        }

        TextView headerTitle = new TextView(this);
        headerTitle.setText("Expense Notification Helper");
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTextSize(18);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setGravity(Gravity.CENTER_VERTICAL);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        headerTitle.setMinHeight(dp(52));
        header.addView(headerTitle, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));

        if (showSettingsInHeader()) {
            TextView settings = new TextView(this);
            settings.setText("\u2699");
            settings.setContentDescription("Settings");
            settings.setTextColor(Color.WHITE);
            settings.setTextSize(26);
            settings.setGravity(Gravity.CENTER);
            settings.setIncludeFontPadding(false);
            settings.setMinWidth(dp(52));
            settings.setMinHeight(dp(52));
            settings.setOnClickListener(v -> openHeaderSettings());
            header.addView(settings, new LinearLayout.LayoutParams(
                    dp(52),
                    dp(52)));
        }

        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        int horizontalPadding = dp(24);
        int contentTopPadding = dp(28);
        int fallbackTop = systemBarHeight("status_bar_height");
        int fallbackBottom = systemBarHeight("navigation_bar_height");
        header.setPadding(dp(26), fallbackTop + dp(20), dp(26), dp(20));
        root.setPadding(horizontalPadding, contentTopPadding, horizontalPadding, dp(16) + fallbackBottom);
        scroll.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                int top = fallbackTop;
                int bottom = fallbackBottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                    top = Math.max(top, systemBars.top);
                    bottom = Math.max(bottom, systemBars.bottom);
                } else {
                    top = Math.max(top, insets.getSystemWindowInsetTop());
                    bottom = Math.max(bottom, insets.getSystemWindowInsetBottom());
                }
                header.setPadding(dp(26), top + dp(20), dp(26), dp(20));
                root.setPadding(
                        horizontalPadding,
                        contentTopPadding,
                        horizontalPadding,
                        dp(16) + bottom);
                return insets;
            });
        }
        outer.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        outer.addView(root, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.addView(outer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    protected boolean showSettingsInHeader() {
        return false;
    }

    protected boolean showBackInHeader() {
        return false;
    }

    protected void openHeaderSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    protected void openHeaderBack() {
        finish();
    }

    protected boolean restrictedSettingsMayApply() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    protected String restrictedSettingsHelpText() {
        String maker = Build.MANUFACTURER == null ? "this device" : Build.MANUFACTURER.trim();
        if (maker.isEmpty()) {
            maker = "this device";
        }
        return "Android 13+ can block Notification access and Accessibility for sideloaded apps. "
                + "If " + maker + " shows \"Restricted setting\", open this app's App info, tap the three-dot menu or More, choose \"Allow restricted settings\", then try the permission again. "
                + "Exact Settings labels vary by manufacturer.";
    }

    protected void openAppInfo() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    protected Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(46));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(rounded(COLOR_TEAL, COLOR_TEAL, 5));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setElevation(0f);
            button.setStateListAnimator(null);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    protected Button secondaryButton(String label) {
        Button button = button(label);
        button.setTextColor(COLOR_TEAL);
        button.setBackground(rounded(COLOR_CARD, COLOR_BORDER, 5));
        return button;
    }

    protected Button dangerButton(String label) {
        Button button = button(label);
        button.setBackground(rounded(COLOR_DANGER, COLOR_DANGER, 5));
        return button;
    }

    protected TextView screenTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        title.setPadding(0, dp(2), 0, dp(10));
        return title;
    }

    protected TextView sectionTitle(String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextColor(COLOR_TEXT);
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(20), 0, dp(6));
        return heading;
    }

    protected TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(14);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    protected void styleCard(LinearLayout card) {
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(rounded(COLOR_CARD, COLOR_BORDER, 6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(12), 0, dp(4));
        card.setLayoutParams(params);
    }

    protected void styleInput(EditText input) {
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(0xff788692);
        input.setTextSize(15);
        input.setBackgroundTintList(ColorStateList.valueOf(COLOR_BORDER));
    }

    protected GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    protected int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    protected int systemBarHeight(String name) {
        int resourceId = getResources().getIdentifier(name, "dimen", "android");
        return resourceId == 0 ? 0 : getResources().getDimensionPixelSize(resourceId);
    }

    /**
     * True when the OS will not Doze-kill this app's process. The notification
     * listener only catches payments live, so a killed process silently drops
     * everything until it is rebound - exemption keeps it alive.
     */
    protected boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** Opens the system dialog asking the user to exempt this app from battery optimization. */
    @SuppressLint("BatteryLife")
    protected void requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            // Already exempt: send the user to the list so they can review or revoke.
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    protected static String emptyDash(String text) {
        return text == null || text.isEmpty() ? "-" : text;
    }
}
