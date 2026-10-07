package app.opendisplay.receiver.input

/**
 * Turns on-screen-keyboard edits into the keystrokes a Mac would see.
 *
 * Soft keyboards edit text in place: they compose a word, rewrite it as they
 * correct it, then commit. The Mac only receives key presses, so we keep the
 * still-composing text and, on every change, backspace over the part that no
 * longer matches before typing the new tail.
 */
class TextForwarder(
    private val type: (String) -> Unit,
    private val backspace: (count: Int) -> Unit,
) {
    private var composing = ""

    fun setComposing(text: String) {
        val keep = commonPrefixLength(composing, text)
        val removed = composing.codePointCount(keep, composing.length)
        if (removed > 0) backspace(removed)
        if (keep < text.length) type(text.substring(keep))
        composing = text
    }

    fun commit(text: String) {
        setComposing(text)
        composing = ""
    }

    /** The IME accepted what we already typed as final. */
    fun finishComposing() {
        composing = ""
    }

    /** `deleteSurroundingText`: the IME erases [count] characters before the cursor. */
    fun deleteBefore(count: Int) {
        if (count <= 0) return
        backspace(count)
        // Whatever it erased from the composing region is gone from the Mac too.
        var left = count
        while (left > 0 && composing.isNotEmpty()) {
            composing = composing.substring(0, composing.offsetByCodePoints(composing.length, -1))
            left--
        }
    }

    /** A hardware-style key (Enter, arrows…) ends any composition in progress. */
    fun interrupted() = finishComposing()

    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        val n = minOf(a.length, b.length)
        while (i < n && a[i] == b[i]) i++
        // Never split a surrogate pair.
        if (i in 1 until a.length && Character.isHighSurrogate(a[i - 1])) i--
        return i
    }
}
