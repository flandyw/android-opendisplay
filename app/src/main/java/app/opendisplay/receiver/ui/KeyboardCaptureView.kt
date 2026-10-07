package app.opendisplay.receiver.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import app.opendisplay.receiver.input.TextForwarder

/**
 * Invisible text target that lets the system keyboard type into the Mac.
 *
 * It never holds text itself: the IME's edits are replayed through
 * [TextForwarder] and everything else (Enter, Backspace, arrows) goes to
 * [onKey]. Suggestions and autocorrect are off so the Mac sees what was typed.
 */
class KeyboardCaptureView(
    context: Context,
    private val forwarder: TextForwarder,
    /** Android key code from the IME; return true when it was forwarded. */
    private val onKey: (KeyEvent) -> Boolean,
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                forwarder.commit(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                forwarder.setComposing(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean {
                forwarder.finishComposing()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                forwarder.deleteBefore(beforeLength)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                forwarder.interrupted()
                return onKey(event)
            }
        }
    }

    fun showKeyboard() {
        requestFocus()
        imm()?.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        imm()?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun imm() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
}
