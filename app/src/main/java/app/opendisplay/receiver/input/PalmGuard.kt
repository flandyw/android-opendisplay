package app.opendisplay.receiver.input

/**
 * Decides whether a finger is a resting palm.
 *
 * A hand writing with a stylus rests on the glass, so a finger that lands while
 * the pen is at work (or just was) is a palm. Two cues, as in a notes app:
 *  - **timing**: the pen touched or hovered within [windowMs]. Hovering keeps it
 *    armed before the tip lands; leaving hover range releases it at once so
 *    finger gestures are not blocked after writing.
 *  - **size**: a contact wider than [maxContactMm] is a hand, pen or no pen.
 *
 * Pure and clock-free (callers pass the event time), so it is unit tested.
 */
class PalmGuard(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxContactMm: Float = DEFAULT_MAX_CONTACT_MM,
) {
    /** Off lets every touch through (finger-only use, or a user preference). */
    @Volatile var enabled = true

    @Volatile private var lastPenAt = NEVER

    /** The pen touched or hovered at [now] (uptime ms). */
    fun penActive(now: Long) {
        lastPenAt = now
    }

    /** The pen left hover range: fingers are fingers again immediately. */
    fun penLeft() {
        lastPenAt = NEVER
    }

    fun penRecent(now: Long): Boolean {
        val at = lastPenAt
        return enabled && at != NEVER && now - at in 0 until windowMs
    }

    fun isLargeContact(majorMm: Float): Boolean = enabled && majorMm > maxContactMm

    /** True when a finger contact of [majorMm] at [now] should be ignored. */
    fun isPalm(majorMm: Float, now: Long): Boolean = penRecent(now) || isLargeContact(majorMm)

    companion object {
        /** How long after pen activity a finger still counts as a resting palm. */
        const val DEFAULT_WINDOW_MS = 500L

        /** Touch major axis above which a contact is a hand rather than a fingertip. */
        const val DEFAULT_MAX_CONTACT_MM = 22f

        private const val NEVER = Long.MIN_VALUE
    }
}

/**
 * Per-contact memory for UI that reads pointer events one at a time (the
 * sidebar): once a touch is judged a palm it stays rejected until it lifts,
 * even if the pen moves away.
 */
class RejectedTouches(private val palm: PalmGuard) {
    private val rejected = HashSet<Long>()

    /** @return true when this event belongs to a palm and should be consumed. */
    fun shouldConsume(id: Long, isTouch: Boolean, isPen: Boolean, pressed: Boolean, now: Long): Boolean {
        if (isPen) {
            palm.penActive(now)
            return false
        }
        if (isTouch && palm.penRecent(now)) rejected += id
        val reject = isTouch && id in rejected
        if (!pressed) rejected -= id
        return reject
    }
}
