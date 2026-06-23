package dev.fanis.expensenotification;

import android.content.Intent;
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
import java.util.Map;

/**
 * Full-screen editor for learned merchant -> payee aliases. Each row edits both
 * fields and auto-saves when you leave a field or the screen; rows can be added
 * and deleted. Saving an edited merchant re-normalizes it into the storage key
 * and drops the previous key if it changed.
 */
public class PayeeAliasesActivity extends BaseActivity {

    private LinearLayout rows;
    private TextView emptyView;
    private Button blockedButton;
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
    protected void onResume() {
        super.onResume();
        if (blockedButton != null) {
            blockedButton.setText(blockedLabel());
        }
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

        root.addView(screenTitle("Payee aliases"));

        TextView hint = bodyText("When a captured payment's merchant matches the top field, the payee below it is prefilled into Expense Manager. The merchant is matched after normalizing. Changes save automatically.");
        root.addView(hint);

        blockedButton = button(blockedLabel());
        blockedButton.setOnClickListener(v -> startActivity(new Intent(this, BlockedMerchantsActivity.class)));
        root.addView(blockedButton);

        Button add = button("Add alias");
        add.setOnClickListener(v -> {
            if (emptyView != null) {
                rows.removeView(emptyView);
                emptyView = null;
            }
            rows.addView(rowView("", ""), 0);
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
        Map<String, String> aliases = PayeeAliases.all(this);
        if (aliases.isEmpty()) {
            emptyView = bodyText("No aliases yet. Tap \"Add alias\" to create one. They are also learned automatically when you pick a payee and save a captured expense.");
            emptyView.setTextSize(14);
            rows.addView(emptyView);
            return;
        }
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            rows.addView(rowView(entry.getKey(), entry.getValue()));
        }
    }

    private View rowView(String key, String payee) {
        LinearLayout card = new LinearLayout(this);
        styleCard(card);

        EditText merchantEdit = new EditText(this);
        merchantEdit.setHint("Merchant (matched text)");
        merchantEdit.setText(key);
        merchantEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        styleInput(merchantEdit);
        card.addView(merchantEdit);

        EditText payeeEdit = new EditText(this);
        payeeEdit.setHint("Payee in Expense Manager");
        payeeEdit.setText(payee);
        payeeEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        styleInput(payeeEdit);
        card.addView(payeeEdit);

        // Tracks the key currently persisted for this row so a rename can drop the
        // old entry; empty for a brand-new, not-yet-saved row.
        final String[] storedKey = {key == null ? "" : key};

        // Persist this row when both fields are filled. Idempotent: safe to run on
        // every blur and again when the screen is paused. A blank field is left
        // alone (use Delete to remove an entry).
        Runnable commit = () -> {
            String newKey = PayeeAliases.normalize(merchantEdit.getText().toString());
            String newPayee = payeeEdit.getText().toString().trim();
            if (newKey.isEmpty() || newPayee.isEmpty()) {
                return;
            }
            if (!storedKey[0].isEmpty() && !storedKey[0].equalsIgnoreCase(newKey)) {
                PayeeAliases.remove(this, storedKey[0]);
            }
            PayeeAliases.set(this, newKey, newPayee);
            storedKey[0] = newKey;
        };
        committers.add(commit);
        View.OnFocusChangeListener saveOnBlur = (v, hasFocus) -> {
            if (!hasFocus) {
                commit.run();
            }
        };
        merchantEdit.setOnFocusChangeListener(saveOnBlur);
        payeeEdit.setOnFocusChangeListener(saveOnBlur);

        Button delete = dangerButton("Delete");
        delete.setOnClickListener(v -> {
            committers.remove(commit);
            if (!storedKey[0].isEmpty()) {
                PayeeAliases.remove(this, storedKey[0]);
            }
            rows.removeView(card);
            Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show();
        });
        card.addView(delete);
        return card;
    }

    private String blockedLabel() {
        int blocked = PayeeAliases.blacklistCount(this);
        return blocked > 0 ? "Blocked merchants (" + blocked + ")" : "Blocked merchants";
    }
}
