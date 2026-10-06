package com.palash.librewolfandroid

import android.view.View
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView

/**
 * Fills one search-engine row and says which engine is the current one.
 *
 * Three screens build this same row -- the toolbar sheet, Settings > Search and
 * Settings > Tabs -- and each of them set the radio button and nothing else. The
 * radio is `clickable=false` and `focusable=false` so it does not eat the row's
 * tap, which also means accessibility services skip it: every row was announced
 * as a bare name and nothing said which engine was actually in use. That is the
 * one piece of state this screen exists to show.
 *
 * Kept in one place so the three cannot drift apart again.
 */
object EngineRow {

    fun bind(
        row: View,
        engine: LibreWolfDefaults.SearchEngine,
        selected: Boolean,
    ) {
        row.findViewById<TextView>(R.id.engine_name).text = engine.name
        // No engine logos or badges: these lists are the radio and the name only.
        row.findViewById<TextView>(R.id.engine_badge).visibility = View.GONE
        row.findViewById<RadioButton>(R.id.engine_radio).isChecked = selected

        // Spoken instead of drawn: the tick alone carries nothing for a screen
        // reader, and the row is the thing that gets focus.
        row.contentDescription = row.context.getString(
            if (selected) R.string.engine_is_default else R.string.engine_not_default,
            engine.name,
        )
        // The row is the control, so it has to be focusable for keyboard and
        // switch-access users to reach it at all.
        row.isFocusable = true
    }

    /**
     * Same row, for a list whose entries are not search engines.
     *
     * Tabs settings reuses this layout for plain radio choices, so the selected
     * state has to be spoken here too rather than only on the engine screens.
     */
    fun bindLabel(row: View, label: String, selected: Boolean) {
        row.findViewById<TextView>(R.id.engine_name).text = label
        row.findViewById<TextView>(R.id.engine_badge).visibility = View.GONE
        row.findViewById<RadioButton>(R.id.engine_radio).isChecked = selected
        row.contentDescription = row.context.getString(
            if (selected) R.string.option_selected else R.string.option_not_selected,
            label,
        )
        row.isFocusable = true
    }

    /** Shows or hides the per-row overflow button, named after the engine. */
    fun bindOverflow(row: View, engine: LibreWolfDefaults.SearchEngine, visible: Boolean) {
        val more = row.findViewById<ImageButton>(R.id.engine_more)
        more.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            more.contentDescription =
                row.context.getString(R.string.a11y_engine_actions, engine.name)
        }
    }
}