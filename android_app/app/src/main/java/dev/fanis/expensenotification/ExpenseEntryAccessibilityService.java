package dev.fanis.expensenotification;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class ExpenseEntryAccessibilityService extends AccessibilityService {
    private static final String EXPENSE_MANAGER_PACKAGE = "com.expensemanager.pro";
    private static final String AMOUNT_ID = "com.expensemanager.pro:id/expenseAmountInput";
    private static final String PAYEE_ID = "com.expensemanager.pro:id/payee";
    private static final String DESCRIPTION_ID = "com.expensemanager.pro:id/expenseDescriptionInput";
    private static final String SAVE_ID = "com.expensemanager.pro:id/expenseSave";
    private static final String SAVE_NEW_ID = "com.expensemanager.pro:id/expenseSaveNew";

    // State machine (stored in the "automation" prefs "state" key):
    //   PENDING    -> fill amount + description, then prefill the payee
    //   AWAIT_SAVE -> wait for the user to tap OK, then learn merchant -> payee
    // Status is intentionally left untouched (no settable default, and the picker
    // dance is not worth it), so the field stays at Expense Manager's blank default.
    private static final String STATE_PENDING = "PENDING";
    private static final String STATE_AWAIT_SAVE = "AWAIT_SAVE";
    private static final String STATE_FILLED = "FILLED";

    private long lastAttemptAt;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences("automation", MODE_PRIVATE);
        String targetPackage = prefs.getString("target_package", EXPENSE_MANAGER_PACKAGE);
        if (!targetPackage.contentEquals(event.getPackageName())) {
            return;
        }

        String state = prefs.getString("state", "");

        // While waiting for the user to finish, keep a live copy of the payee they
        // have chosen, and commit the alias both when they tap OK and (as a fallback,
        // in case the click event isn't delivered) as soon as a real payee appears.
        if (STATE_AWAIT_SAVE.equals(state)) {
            cacheAndLearnPayee(prefs);
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED && isSaveClick(event)) {
                prefs.edit().putString("state", STATE_FILLED).apply();
            }
            return;
        }

        if (!STATE_PENDING.equals(state)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < 500) {
            return;
        }
        lastAttemptAt = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }

        boolean amountSet = setText(root, prefs.getString("amount_id", AMOUNT_ID), prefs.getString("amount", ""));
        setText(root, prefs.getString("description_id", DESCRIPTION_ID), prefs.getString("description", ""));

        // Only advance once the form is actually rendered (amount field present).
        if (amountSet) {
            prefillPayeeAndAwaitSave(prefs, root);
        }
    }

    @Override
    public void onInterrupt() {
    }

    // Types the (alias-resolved) payee minus its last character to surface Expense
    // Manager's saved-payee autocomplete, then hands off: the user selects the right
    // entry from the dropdown themselves, and we learn their choice on save.
    private void prefillPayeeAndAwaitSave(SharedPreferences prefs, AccessibilityNodeInfo root) {
        String prefillText = "";
        if (root != null) {
            String payee = prefs.getString("payee", "");
            if (payee != null && !payee.isEmpty()) {
                AccessibilityNodeInfo payeeField = findById(root, prefs.getString("payee_id", PAYEE_ID));
                if (payeeField != null) {
                    payeeField.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    payeeField.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    prefillText = payee.length() > 1 ? payee.substring(0, payee.length() - 1) : payee;
                    setText(payeeField, prefillText);
                }
            }
        }
        // Remember exactly what we typed so we never mistake our own partial prefill
        // for the user's chosen payee when learning.
        prefs.edit()
                .putString("prefill_text", prefillText)
                .putString("state", STATE_AWAIT_SAVE)
                .apply();
    }

    private boolean isSaveClick(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            return false;
        }
        CharSequence viewId = source.getViewIdResourceName();
        if (viewId == null) {
            return false;
        }
        String configuredIds = getSharedPreferences("automation", MODE_PRIVATE)
                .getString("save_ids", SAVE_ID + "\n" + SAVE_NEW_ID);
        for (String saveId : configuredIds.split("\\n")) {
            if (saveId != null && !saveId.isEmpty() && saveId.contentEquals(viewId)) {
                return true;
            }
        }
        return false;
    }

    // Reads the payee currently in the form and, if it is a real choice distinct
    // from the raw merchant, records merchant -> payee. Runs on every event while
    // awaiting save (cheap) so the alias is captured even if the OK click event is
    // never delivered or the form closes before we can read it.
    private void cacheAndLearnPayee(SharedPreferences prefs) {
        String merchant = prefs.getString("merchant", "");
        if (merchant == null || merchant.isEmpty()) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo payeeField = findById(root, prefs.getString("payee_id", PAYEE_ID));
        if (payeeField == null || payeeField.getText() == null) {
            return;
        }
        String payee = payeeField.getText().toString().trim();
        String prefill = prefs.getString("prefill_text", "");
        // Ignore empties, the raw merchant, and our own partial prefill: none of
        // those represent a payee the user actually chose.
        if (payee.isEmpty() || payee.equalsIgnoreCase(merchant.trim())
                || (prefill != null && payee.equalsIgnoreCase(prefill.trim()))) {
            return;
        }
        PayeeAliases.learn(this, merchant, payee);
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                .putString("last_alias_learn", merchant + " -> " + payee)
                .apply();
    }

    private boolean setText(AccessibilityNodeInfo root, String viewId, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        AccessibilityNodeInfo node = findById(root, viewId);
        return node != null && setText(node, value);
    }

    private boolean setText(AccessibilityNodeInfo node, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private AccessibilityNodeInfo findById(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        return nodes == null || nodes.isEmpty() ? null : nodes.get(0);
    }
}
