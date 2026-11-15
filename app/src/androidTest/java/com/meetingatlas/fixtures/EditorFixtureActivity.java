package com.meetingatlas.fixtures;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Test APK only; it runs as a separate package and never transmits or saves what is typed. */
public final class EditorFixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int spacing = (int) (16 * getResources().getDisplayMetrics().density);
        column.setPadding(spacing, spacing * 3, spacing, spacing);
        TextView label = new TextView(this);
        label.setText("Atlas QA — independent editor");
        column.addView(label);
        EditText draft = editor(column, "Message draft", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        draft.setText("Before · after");
        draft.requestFocus();
        draft.setSelection(7);
        editor(column, "Second message", InputType.TYPE_CLASS_TEXT);
        editor(column, "Example password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editor(column, "Numeric entry", InputType.TYPE_CLASS_NUMBER);
        editor(column, "Payment card number", InputType.TYPE_CLASS_TEXT);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    private EditText editor(LinearLayout column, String hint, int type) {
        EditText editor = new EditText(this);
        editor.setHint(hint);
        editor.setInputType(type);
        column.addView(editor, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return editor;
    }
}
