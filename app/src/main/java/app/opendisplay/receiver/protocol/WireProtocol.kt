package app.opendisplay.receiver.protocol

/**
 * Mirrors Shared/Protocol.swift — bump only when the wire changes.
 * See WIRE.md at the repo root.
 */
object WireProtocol {
    /** 3 = optional AUD1 system-audio frames when hello advertises audio=1. */
    const val VERSION = 3
    const val MIN_SUPPORTED_PEER = 1
    const val ASSUMED_WHEN_ABSENT = 1

    const val DEFAULT_PORT: Int = 9000
    /** Mac listens here when it cannot dial the tablet (AP client isolation). */
    const val MAC_REVERSE_PORT: Int = 9011
    /** Android NsdManager requires the trailing period; Bonjour peers still match. */
    const val SERVICE_TYPE = "_opensidecar._tcp."
    /** Mac host advertisement for reverse dial (tablet → Mac). */
    const val MAC_HOST_SERVICE_TYPE = "_opendisplay-mac._tcp."
}

object WireMessage {
    const val WELCOME = "welcome"
    const val UPDATE_REQUIRED = "updateRequired"
    const val SLEEPING = "sleeping"
    const val CLOSING = "closing"
    /** Mac → Android: user disconnected; pause reverse auto-connect until a new Connect. */
    const val DISCONNECT = "disconnect"
    const val HELLO = "hello"
    const val TOUCH = "touch"
    const val SCROLL = "scroll"
    const val PING = "ping"
    const val PONG = "pong"
    const val STATS = "stats"
    const val KF = "kf"
    const val CURSOR = "cursor"
    const val CURSOR_IMG = "cursorImg"
    /** Visible content rect while pinch-zoomed — Mac crops capture for sharp zoom. */
    const val VIEWPORT = "viewport"
    /** Keyboard / shortcut event (Android → Mac). See [WireCaps.KEY]. */
    const val KEY = "key"
    /** Explicit click with a button, e.g. right-click (Android → Mac). See [WireCaps.CLICK]. */
    const val CLICK = "click"
    /** Plain-text clipboard, both directions. See [WireCaps.CLIP]. */
    const val CLIP = "clip"
    /** Mirror vs extended desktop request (Android → Mac). See [WireCaps.MODE]. */
    const val MODE = "mode"
    /** Mac → tablet: the pairing token to present on later reverse connections. */
    const val PAIR = "pair"
    /** PNG clipboard image, both directions. See [WireCaps.CLIP_IMAGE]. */
    const val CLIP_IMAGE = "clipimg"
}

/**
 * Optional features. The Mac lists the ones it understands in `welcome.caps`;
 * the tablet only sends a feature's messages when the Mac listed it, so older
 * Mac builds never see messages they would misread. The tablet lists what it
 * can send in `hello.ext`.
 */
object WireCaps {
    /** `touch` phase "hover" for pen / mouse hover before contact. */
    const val HOVER = "hover"
    const val KEY = "key"
    const val CLICK = "click"
    const val CLIP = "clip"
    const val MODE = "mode"
    /** Mac crops its capture to the pinch-zoomed rect (`viewport` messages). */
    const val VIEWPORT = "viewport"
    /** Images travel with the clipboard; needs [CLIP] too. */
    const val CLIP_IMAGE = "clipimg"

    /** What this tablet can send or receive; sent as `hello.ext`. */
    val ALL = listOf(HOVER, KEY, CLICK, CLIP, MODE, CLIP_IMAGE)
}
