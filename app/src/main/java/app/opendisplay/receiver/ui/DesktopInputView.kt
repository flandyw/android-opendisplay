package app.opendisplay.receiver.ui

import android.content.Context
import android.view.PointerIcon
import android.widget.FrameLayout

/** Reports actual capture state, including Android releasing it on window focus loss. */
class DesktopInputView(context: Context) : FrameLayout(context) {
    var onCaptureChanged: (Boolean) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun showRemotePointer(remote: Boolean) {
        pointerIcon = PointerIcon.getSystemIcon(context, if (remote) PointerIcon.TYPE_NULL else PointerIcon.TYPE_ARROW)
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        onCaptureChanged(hasCapture)
    }
}
