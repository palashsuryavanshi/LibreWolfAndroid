package com.palash.librewolfandroid

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter

/**
 * Locale-aware formatting.
 *
 * The obvious ways to write these — `"%.1f %s".format(bytes / 1024.0, "MB")` and
 * a `SimpleDateFormat("MMM d")` — are wrong outside English: the decimal comma
 * and the thousands separator follow the device locale, the month name is
 * localised, and units are not "MB" everywhere. `Formatter` and `DateUtils` come
 * from the platform and already do all of that.
 */
object Format {

    /** A byte count with a localised unit, e.g. "1.5 MB" or "1,5 MB". */
    fun bytes(context: Context, count: Long): String =
        if (count <= 0L) Formatter.formatFileSize(context, 0L) else Formatter.formatFileSize(context, count)

    /** Progress as a whole percentage, clamped, in the device's numerals. */
    fun percent(done: Long, total: Long): String {
        if (total <= 0L) return "0%"
        val value = ((done * 100L) / total).coerceIn(0L, 100L)
        return "$value%"
    }

    /**
     * A timestamp as "2 minutes ago", falling back to a date once it is old
     * enough that a relative phrase stops being useful.
     */
    fun relativeTime(context: Context, timestamp: Long): String =
        DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()

    /** A duration in seconds as m:ss, or h:mm:ss once it passes an hour. */
    fun duration(seconds: Long): String {
        if (seconds < 0) return "0:00"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, secs)
        }
    }
}
