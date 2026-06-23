package dev.fanis.expensenotification;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor for blocked merchants: platforms (Wolt, Bolt) whose payment notification
 * names the platform, not the real payee, so they must never be auto-learned or
 * auto-mapped. Reached from the Payee aliases screen. Each row edits one merchant
 * and auto-saves when you leave the field or the screen; rows can be added and
 * deleted.
 */
public class BlockedMerchantsActivity extends BaseActivity {

    private LinearLayout rows;
    private TextView emptyView;
    // One committer per live row; run on field blur and when leaving the screen so
    // edits persist without a Save button. Removed when a row is deleted.
    private final List<Runnable> committers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        renderRows();
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (Runnable committer : new ArrayList<>(committers)) {
            committer.run();
        }
    }

    @Override
    protected boolean showBackInHeader() {
        return true;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        ScrollView scroll = scrollRoot(root);

        root.addView(screenTitle("Blocked merchants"));

        TextView hint = bodyText("Merchants listed here are never auto-mapped or auto-learned. Use this for delivery platforms where the real payee differs every time. Changes save automatically.");
        root.addView(hint);

        Button add = button("Add blocked merchant");
        add.setOnClickListener(v -> {
            if (emptyView != null) {
                rows.removeView(emptyView);
                emptyView = null;
            }
            rows.addView(rowView(""), 0);
        });
        root.addView(add);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);
        return scroll;
    }

    private void renderRows() {
        rows.removeAllViews();
        emptyView = null;
        List<String> keys = PayeeAliases.blacklistKeys(this);
        if (keys.isEmpty()) {
            emptyView = bodyText("No blocked merchants. Tap \"Add blocked merchant\" to add one.");
            emptyView.setTextSize(14);
            rows.addView(emptyView);
            return;
        }
        for (String key : keys) {
            rows.addView(rowView(key));
        }
    }

    private View rowView(String key) {
        LinearLayout card = new LinearLayout(this);
        styleCard(card);

        EditText merchantEdit = new EditText(this);
        merchantEdit.setHint("Merchant to never map (e.g. Wolt)");
        merchantEdit.setText(key);
        merchantEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        styleInput(merchantEdit);
        card.addView(merchantEdit);

        // Key currently persisted for this row; empty for a new, unsaved row.
        final String[] storedKey = {key == null ? "" : key};

        // Persist this row when the field is filled. Idempotent: safe to run on
        // every blur and again when the screen is paused. A blank field is left
        // alone (use Delete to remove an entry).
        Runnable commit = () -> {
            String newKey = PayeeAliases.normalize(merchantEdit.getText().toString());
            if (newKey.isEmpty()) {
                return;
            }
            if (!storedKey[0].isEmpty() && !storedKey[0].equalsIgnoreCase(newKey)) {
                PayeeAliases.unblacklist(this, storedKey[0]);
            }
            PayeeAliases.blacklist(this, newKey);
            storedKey[0] = newKey;
        };
        committers.add(commit);
        merchantEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                commit.run();
            }
        });

        Button delete = dangerButton("Delete");
        delete.setOnClickListener(v -> {
            committers.remove(commit);
            if (!storedKey[0].isEmpty()) {
                PayeeAliases.unblacklist(this, storedKey[0]);
            }
            rows.removeView(card);
            Toast.makeText(this, "Removed.", Toast.LENGTH_SHORT).show();
        });
        card.addView(delete);
        return card;
    }
}
