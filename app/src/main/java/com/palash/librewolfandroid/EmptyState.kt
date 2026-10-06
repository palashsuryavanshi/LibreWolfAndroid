package com.palash.librewolfandroid

import android.view.View
import android.widget.TextView

/**
 * Drives the shared empty-state view that sits beside a list.
 *
 * Four list screens needed the same thing and three of them did it wrongly: a Toast
 * as the empty state, which has faded before the user has read it and is never
 * spoken at all, and one screen with no empty state whatsoever. This keeps the
 * behaviour in one place so a list cannot half-do it.
 *
 * The list and the message are 0dp/weight-1 siblings of equal weight, so hiding
 * one is what gives the space to the other. That is why [list] is toggled here as
 * well: leaving it VISIBLE while it is empty leaves it claiming the whole area and
 * pushes the message off the bottom of the screen.
 */
class EmptyState(
    private val list: View,
    private val container: View,
    private val text: TextView,
    private val hint: TextView,
) {

    /**
 * Shows the message when [count] is zero and the list otherwise.
     *
     * [message] and [hintText] are resolved only when the screen is actually
     * empty, so a caller can pass a lambda that reads the current filter without
     * paying for it on every bind.
     */
    fun bind(count: Int, message: () -> CharSequence, hintText: (() -> CharSequence)? = null) {
        if (count > 0) {
            container.visibility = View.GONE
            list.visibility = View.VISIBLE
            return
        }
        text.text = message()
        val hint = hintText
        if (hint == null) {
            this.hint.visibility = View.GONE
        } else {
            this.hint.text = hint()
            this.hint.visibility = View.VISIBLE
        }
        container.visibility = View.VISIBLE
        list.visibility = View.GONE
    }

    /**
     * Whether an empty screen is showing.
     *
     * Used by the tests and by callers that need to tell "nothing here" from
     * "nothing in this filter" before deciding what to say.
     */
    val isShowing: Boolean
        get() = container.visibility == View.VISIBLE

    companion object {
        /** Wires an included [R.layout.view_empty_state] to the list beside it. */
        fun of(root: View, listId: Int, includeId: Int): EmptyState {
            val container = root.findViewById<View>(includeId)
            return EmptyState(
                list = root.findViewById(listId),
                container = container,
                text = container.findViewById(R.id.empty_text),
                hint = container.findViewById(R.id.empty_hint),
            )
        }
    }
}