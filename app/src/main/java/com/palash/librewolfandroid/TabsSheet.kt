package com.palash.librewolfandroid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Tab tray: Tabs / Private / Inactive / Groups pages, List or Grid layout,
 * swipe-to-close, trackers pill and new-tab FAB.
 */
class TabsSheet : BottomSheetDialogFragment() {

    companion object {
        const val MODE_TABS = 0
        const val MODE_PRIVATE = 1
        const val MODE_INACTIVE = 2
        const val MODE_GROUPS = 3
    }

    var tabs: List<TabItem> = emptyList()
    var inactiveTabs: List<TabItem> = emptyList()
    var trackersText: String = ""
    var mode: Int = MODE_TABS
    var columns: Int = 2
    var showGroups: Boolean = false
    var onSelect: (Long) -> Unit = {}
    var onClose: (Long) -> Unit = {}
    var onCloseAll: (Boolean) -> Unit = {}
    var onNewTab: (Boolean) -> Unit = {}
    var onTabMenu: (TabItem) -> Unit = {}

    private var adapter: TabsAdapter? = null
    private var groupedAdapter: GroupedTabsAdapter? = null
    // paint() used to re-set the LayoutManager and the adapter on every call,
    // and refresh() is called on every progress tick. Re-setting the layout
    // manager resets the scroll position, so the tray visibly jumped whenever
    // a page loaded underneath it. Structure is now only rebuilt when the mode
    // or column count actually changes; data updates go through DiffUtil.
    private var paintedMode = -1
    private var paintedColumns = -1
    private var trackersView: TextView? = null
    private var countView: TextView? = null
    private var emptyView: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_tabs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.tabs_list)
        adapter = TabsAdapter(
            visible(),
            { onSelect(it); dismiss() },
            { onClose(it) },
            onTabMenu,
        )
        groupedAdapter = GroupedTabsAdapter(
            visible(),
            { onSelect(it); dismiss() },
            { onClose(it) },
            onTabMenu,
        )
        list.adapter = adapter
        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(
                    rv: RecyclerView,
                    vh: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean = false

                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                    val pos = vh.bindingAdapterPosition
                    val item = if (list.adapter === groupedAdapter) {
                        groupedAdapter?.itemAt(pos)
                    } else {
                        adapter?.get(pos)
                    }
                    item?.let { onClose(it.id) }
                }
            },
        ).attachToRecyclerView(list)

        trackersView = view.findViewById(R.id.tray_trackers)
        countView = view.findViewById(R.id.tray_count)
        emptyView = view.findViewById(R.id.tray_empty)

        view.findViewById<TextView>(R.id.mode_tabs).apply {
            setOnClickListener { switchMode(MODE_TABS) }
        }
        view.findViewById<TextView>(R.id.mode_private).apply {
            setOnClickListener { switchMode(MODE_PRIVATE) }
        }
        view.findViewById<TextView>(R.id.mode_inactive).apply {
            visibility = if (inactiveTabs.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener { switchMode(MODE_INACTIVE) }
        }
        view.findViewById<TextView>(R.id.mode_groups).apply {
            visibility = if (showGroups) View.VISIBLE else View.GONE
            setOnClickListener { switchMode(MODE_GROUPS) }
        }
        view.findViewById<View>(R.id.tray_close_all).setOnClickListener {
            onCloseAll(mode == MODE_PRIVATE)
        }
        view.findViewById<View>(R.id.sheet_new_tab).setOnClickListener {
            onNewTab(mode == MODE_PRIVATE)
            dismiss()
        }
        paint()
    }

    private fun visible(): List<TabItem> = when (mode) {
        MODE_PRIVATE -> tabs.filter { it.isPrivate }
        MODE_INACTIVE -> inactiveTabs
        MODE_GROUPS -> tabs.filter { !it.isPrivate }
        else -> tabs.filter { !it.isPrivate }
    }

    private fun switchMode(next: Int) {
        if (mode == next) return
        mode = next
        paint()
    }

    private fun chip(id: Int, active: Boolean) {
        val ctx = context ?: return
        view?.findViewById<TextView>(id)?.apply {
            setBackgroundResource(if (active) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            setTextColor(ctx.getColor(if (active) R.color.librewolf_text else R.color.librewolf_grey))
        }
    }

    private fun paint() {
        val list = view?.findViewById<RecyclerView>(R.id.tabs_list) ?: return
        val shown = visible()
        val structural = mode != paintedMode || columns != paintedColumns
        paintedMode = mode
        paintedColumns = columns
        if (mode == MODE_GROUPS) {
            if (structural || list.adapter !== groupedAdapter) {
                list.layoutManager = LinearLayoutManager(requireContext())
                list.adapter = groupedAdapter
            }
            groupedAdapter?.update(shown)
        } else {
            if (structural || list.adapter !== adapter) {
                list.layoutManager = if (columns == 1) {
                    LinearLayoutManager(requireContext())
                } else {
                    GridLayoutManager(requireContext(), 2)
                }
                list.adapter = adapter
            }
            adapter?.update(shown)
        }
        trackersView?.text = trackersText
        countView?.text = shown.size.toString()
        emptyView?.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        emptyView?.text = getString(
            when (mode) {
                MODE_PRIVATE -> R.string.no_private_tabs
                MODE_INACTIVE -> R.string.no_inactive_tabs
                else -> R.string.no_tabs
            },
        )
        chip(R.id.mode_tabs, mode == MODE_TABS)
        chip(R.id.mode_private, mode == MODE_PRIVATE)
        chip(R.id.mode_inactive, mode == MODE_INACTIVE)
        chip(R.id.mode_groups, mode == MODE_GROUPS)
    }

    fun refresh(tabs: List<TabItem>, inactiveTabs: List<TabItem>, trackersText: String) {
        this.tabs = tabs
        this.inactiveTabs = inactiveTabs
        this.trackersText = trackersText
        // Refresh arrives on every progress tick, title change and location
        // change. When the sheet is not on screen there is nothing to paint:
        // the data is cached above and painted on view creation. Painting a
        // hidden sheet was pure waste, and updating a detached adapter risked
        // rebinding rows nobody can see.
        if (dialog?.isShowing != true) return
        view?.findViewById<TextView>(R.id.mode_inactive)?.visibility =
            if (inactiveTabs.isEmpty()) View.GONE else View.VISIBLE
        paint()
    }
}
