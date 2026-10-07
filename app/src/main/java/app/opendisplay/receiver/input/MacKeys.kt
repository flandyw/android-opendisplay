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
}

/** macOS virtual key codes (kVK_*, ANSI layout) and Android → Mac translation. */
object MacKeys {
    const val RETURN = 36
    const val ESCAPE = 53
    const val Z = 6
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
        put(KeyEvent.KEYCODE_TAB, 48)
        put(KeyEvent.KEYCODE_SPACE, 49)
        put(KeyEvent.KEYCODE_GRAVE, 50)
        put(KeyEvent.KEYCODE_DEL, 51) // Android DEL is backspace
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
    }

    /** @return the Mac virtual key code, or null for keys we do not forward. */
    fun fromAndroid(keyCode: Int): Int? = map[keyCode]
}
