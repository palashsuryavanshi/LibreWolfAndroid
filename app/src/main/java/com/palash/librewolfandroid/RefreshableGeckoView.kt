package com.palash.librewolfandroid

import android.content.Context
import android.util.AttributeSet
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * A [GeckoView] that knows whether its page can still be scrolled up.
 *
 * ## Why this exists
 *
 * `SwipeRefreshLayout` decides whether to take over a downward pull by asking
 * its child `canScrollVertically(-1)`. GeckoView extends FrameLayout and does
 * not override that, so it always answers "no" -- which would make the pull
 * fire a refresh at every scroll position, not only at the top of the page.
 *
 * GeckoView exposes no getter for the page's scroll offset, so the offset is
 * tracked from [GeckoSession.ScrollDelegate.onScrollChanged] and answered from
 * that.
 *
 * Only the upward case is overridden. The downward case is left to the
 * superclass: the engine's own scroll view handles it, and answering "yes"
 * there would stop the pull from ever reaching the refresh layout.
 */
class RefreshableGeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GeckoView(context, attrs) {

    /**
     * The page's vertical scroll offset in CSS pixels, as last reported by the
     * engine. Zero means the page is at the top, which is the only position at
     * which a downward pull should refresh rather than scroll.
     */
    @Volatile
    var pageScrollY: Int = 0
        private set

    fun onPageScrolled(x: Int, y: Int) {
        pageScrollY = y
        android.util.Log.i("RefreshableGeckoView", "pageScrolled y=$y")
    }

    override fun canScrollVertically(direction: Int): Boolean {
        if (direction < 0) return pageScrollY > 0
        return super.canScrollVertically(direction)
    }
}