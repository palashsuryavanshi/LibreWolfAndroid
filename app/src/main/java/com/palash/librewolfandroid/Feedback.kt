package com.palash.librewolfandroid

import android.app.Activity
import android.view.View
import android.widget.Toast

/**
 * Confirms an action in a way a screen reader will actually deliver.
 *
 * A Toast is a sighted user's confirmation and little more: TalkBack does not
 * reliably announce it, and it is gone within a couple of seconds. Every one of
 * these messages reports something that has already happened and has no other
 * visible trace -- a row removed, a download paused, a share that found nothing
 * -- so the confirmation is the only feedback there will ever be.
 *
 * Both channels are used deliberately rather than as a fallback: sighted users
 * get the Toast, screen-reader users get the announcement, and neither has to
 * wait for the other.
 *
 * On an Activity rather than a Context because the announcement has to come from
 * a view in the window the user is looking at, and `Context` cannot reach one.
 */
fun Activity.confirm(@androidx.annotation.StringRes message: Int) {
    confirm(getString(message))
}

/** As [confirm], for a message that is built rather than looked up. */
fun Activity.confirm(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    findViewById<View>(android.R.id.content)?.announceForAccessibility(text)
}

/**
 * Runs a share, and says so when there is nowhere to share to.
 *
 * `startActivity` on a chooser with no handler throws, and a cancelled chooser
 * gives no result at all. Both used to be silent, so sharing to a device with no
 * compatible app looked like the button did nothing.
 */
fun Activity.shareOrExplain(intent: android.content.Intent, noTargetMessage: Int) {
    try {
        startActivity(intent)
    } catch (_: Exception) {
        confirm(noTargetMessage)
    }
}