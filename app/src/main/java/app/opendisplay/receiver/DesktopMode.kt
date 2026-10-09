package app.opendisplay.receiver

import android.content.Context

/**
 * How the Mac's desktop is shown here. [EXTEND] adds a virtual display; [MIRROR]
 * shows the Mac's main display. The Mac owns the mode, so this is the one the
 * connection screen asks for when a session starts. The sidebar can still change
 * it during a session.
 */
enum class DesktopMode {
    EXTEND,
    MIRROR,
    ;

    companion object {
        private const val PREFS = "opendisplay"
        private const val KEY = "desktopMode"

        /** Unknown or missing names fall back to [EXTEND], the Mac's own default. */
        fun fromName(name: String?): DesktopMode = entries.firstOrNull { it.name == name } ?: EXTEND

        fun load(context: Context): DesktopMode =
            fromName(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        fun save(context: Context, mode: DesktopMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, mode.name)
                .apply()
        }
    }
}
