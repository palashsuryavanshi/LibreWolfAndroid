package com.palash.librewolfandroid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Inline search-engine selector shown from the toolbar pill. */
class EnginePickerSheet : BottomSheetDialogFragment() {

    var onPick: (LibreWolfDefaults.SearchEngine) -> Unit = {}
    var onAddCustom: () -> Unit = {}

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_engines, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val store = PrivacyStore(requireContext())
        val list = view.findViewById<android.widget.LinearLayout>(R.id.engines_sheet_list)
        val engines = LibreWolfDefaults.SEARCH_ENGINES + store.customEngines
        val current = store.engineFor(false)
        engines.forEach { engine ->
            val row = layoutInflater.inflate(R.layout.item_engine_radio, list, false)
            row.findViewById<TextView>(R.id.engine_name).text = engine.name
            row.findViewById<TextView>(R.id.engine_badge).visibility = View.GONE
            row.findViewById<RadioButton>(R.id.engine_radio).isChecked = engine.name == current.name
            row.setOnClickListener {
                onPick(engine)
                dismiss()
            }
            list.addView(row)
        }
        view.findViewById<View>(R.id.engines_sheet_add).setOnClickListener {
            dismiss()
            onAddCustom()
        }
    }
}
