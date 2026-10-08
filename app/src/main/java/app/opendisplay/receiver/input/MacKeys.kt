package app.opendisplay.receiver.input

import android.view.KeyEvent

/** macOS modifier bitmask used by `key` and `touch` messages. */
object Mods {
    const val SHIFT = 1
    const val CTRL = 2
    const val OPT = 4
    const val CMD = 8

    fun fromMetaState(meta: Int): Int {
        var m = 0
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or SHIFT
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or CTRL
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or OPT
        if (meta and KeyEvent.META_META_ON != 0) m = m or CMD
        return m
    }

    fun remapControl(mods: Int, controlIsCommand: Boolean): Int =
        if (controlIsCommand && mods and CTRL != 0) (mods and CTRL.inv()) or CMD else mods
}

/** macOS virtual key codes (kVK_*, ANSI layout) and Android → Mac translation. */
object MacKeys {
    const val RETURN = 36
    const val DELETE = 51 // backspace
    const val F2 = 120
    const val ESCAPE = 53
    const val Z = 6
    const val X = 7
    const val D = 2
    const val TAB = 48
    const val SPACE = 49
    const val C = 8
    const val V = 9
    const val ARROW_LEFT = 123
    const val ARROW_RIGHT = 124
    const val ARROW_DOWN = 125
    const val ARROW_UP = 126

    private val map: Map<Int, Int> = buildMap {
        val letters = intArrayOf(
            0, 11, 8, 2, 14, 3, 5, 4, 34, 38, 40, 37, 46, // A..M
            45, 31, 35, 12, 15, 1, 17, 32, 9, 13, 7, 16, 6, // N..Z
        )
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, letters[i])
        val digits = intArrayOf(29, 18, 19, 20, 21, 23, 22, 26, 28, 25) // 0..9
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, digits[i])
        put(KeyEvent.KEYCODE_ENTER, RETURN)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, RETURN)
        put(KeyEvent.KEYCODE_TAB, TAB)
        put(KeyEvent.KEYCODE_SPACE, SPACE)
        put(KeyEvent.KEYCODE_GRAVE, 50)
        put(KeyEvent.KEYCODE_DEL, DELETE) // Android DEL is backspace
        put(KeyEvent.KEYCODE_ESCAPE, ESCAPE)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 117)
        put(KeyEvent.KEYCODE_MINUS, 27)
        put(KeyEvent.KEYCODE_EQUALS, 24)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 33)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 30)
        put(KeyEvent.KEYCODE_BACKSLASH, 42)
        put(KeyEvent.KEYCODE_SEMICOLON, 41)
        put(KeyEvent.KEYCODE_APOSTROPHE, 39)
        put(KeyEvent.KEYCODE_COMMA, 43)
        put(KeyEvent.KEYCODE_PERIOD, 47)
        put(KeyEvent.KEYCODE_SLASH, 44)
        put(KeyEvent.KEYCODE_MOVE_HOME, 115)
        put(KeyEvent.KEYCODE_MOVE_END, 119)
        put(KeyEvent.KEYCODE_PAGE_UP, 116)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 121)
        put(KeyEvent.KEYCODE_DPAD_LEFT, ARROW_LEFT)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, ARROW_RIGHT)
        put(KeyEvent.KEYCODE_DPAD_DOWN, ARROW_DOWN)
        put(KeyEvent.KEYCODE_DPAD_UP, ARROW_UP)
        val fkeys = intArrayOf(122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111)
        for (i in fkeys.indices) put(KeyEvent.KEYCODE_F1 + i, fkeys[i])
        // kVK_ANSI_Keypad0..9 (the gaps are Apple's, not ours).
        val keypad = intArrayOf(82, 83, 84, 85, 86, 87, 88, 89, 91, 92)
        for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, keypad[i])
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 65)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 67)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 69)
        put(KeyEvent.KEYCODE_NUM_LOCK, 71) // keypad Clear
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 75)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 78)
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, 81)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 56)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 60)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 59)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 62)
        put(KeyEvent.KEYCODE_ALT_LEFT, 58)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 61)
        put(KeyEvent.KEYCODE_META_LEFT, 55)
        put(KeyEvent.KEYCODE_META_RIGHT, 54)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 57)
    }

    private const val US_UNSHIFTED = "`1234567890-=qwertyuiop[]\\asdfghjkl;'zxcvbnm,./ "
    private const val US_SHIFTED = "~!@#$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:\"ZXCVBNM<>? "

    /** A key plus whether Shift must be held to produce the character. */
    data class CharKey(val code: Int, val shift: Boolean)

    /**
     * Soft-keyboard text → the ANSI key that types it, so shortcuts held with a
     * sidebar modifier (⌘ + "c") hit the right key. Characters outside US ANSI
     * return null and are sent as unicode text instead.
     */
    fun fromChar(c: Char): CharKey? {
        val lower = US_UNSHIFTED.indexOf(c)
        if (lower >= 0) return CharKey(keyForLayoutIndex(lower), false)
        val upper = US_SHIFTED.indexOf(c)
        if (upper >= 0) return CharKey(keyForLayoutIndex(upper), true)
        return null
    }

    /** Positions in [US_UNSHIFTED] → kVK codes, in the same order as the string. */
    private val layoutCodes = intArrayOf(
        50, 18, 19, 20, 21, 23, 22, 26, 28, 25, 29, 27, 24, // ` 1..0 - =
        12, 13, 14, 15, 17, 16, 32, 34, 31, 35, 33, 30, 42, // q..p [ ] \
        0, 1, 2, 3, 5, 4, 38, 40, 37, 41, 39, // a..l ; '
        6, 7, 8, 9, 11, 45, 46, 43, 47, 44, // z..m , . /
        49, // space
    )

    private fun keyForLayoutIndex(i: Int) = layoutCodes[i]

    /** @return the Mac virtual key code, or null for keys we do not forward. */
    fun fromAndroid(keyCode: Int): Int? = map[keyCode]

    fun modifierFor(code: Int): Int = when (code) {
        56, 60 -> Mods.SHIFT
        59, 62 -> Mods.CTRL
        58, 61 -> Mods.OPT
        55, 54 -> Mods.CMD
        else -> 0
    }

    fun mappedCode(keyCode: Int, controlIsCommand: Boolean): Int? = when {
        controlIsCommand && keyCode == KeyEvent.KEYCODE_CTRL_LEFT -> 55
        controlIsCommand && keyCode == KeyEvent.KEYCODE_CTRL_RIGHT -> 54
        else -> fromAndroid(keyCode)
    }
}
