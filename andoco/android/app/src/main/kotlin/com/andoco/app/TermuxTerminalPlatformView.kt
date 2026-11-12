package com.andoco.app

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class TermuxTerminalViewFactory(private val messenger: BinaryMessenger) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val params = args as? Map<*, *> ?: emptyMap<String, Any>()
        val sessionId = params["sessionId"] as? String ?: OpencodeService.MAIN_SESSION
        return TermuxTerminalPlatformView(context, sessionId)
    }
}

class TermuxTerminalPlatformView(context: Context, private val sessionId: String) : PlatformView {
    private val terminalView: TerminalView = TerminalView(context, null)
    private var session: TerminalSession? = null
    private var attached = false

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { terminalView.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(changedSession: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(changedSession: TerminalSession?) {}
        override fun onBell(changedSession: TerminalSession) {}
        override fun onColorsChanged(changedSession: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(changedSession: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: android.view.MotionEvent?) {
            terminalView.requestFocus()
            val imm = terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, s: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?): Boolean = false
        override fun onLongPress(event: android.view.MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    init {
        terminalView.setTerminalViewClient(viewClient)
        val density = context.resources.displayMetrics.density
        // Match Termux-like sizing in sp-ish px; remove any implicit insets.
        val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, context.resources.displayMetrics).toInt().coerceAtLeast(24)
        terminalView.setTextSize(textSizePx)
        terminalView.setPadding(0, 0, 0, 0)
        terminalView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        terminalView.isVerticalScrollBarEnabled = true
        terminalView.isHorizontalScrollBarEnabled = false
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.setOnClickListener {
            terminalView.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
        tryAttach()
    }

    private fun tryAttach() {
        if (attached) return
        val io = OpencodeService.getSessionIo(sessionId) ?: OpencodeService.ensureSessionAndGetIo(sessionId)
        if (io == null) {
            terminalView.postDelayed({ tryAttach() }, 300)
            return
        }
        val s = TerminalSession.createExternal(io.input, io.output, 2000, sessionClient)
        session = s
        terminalView.attachSession(s)
        attached = true
        terminalView.post {
            terminalView.requestFocus()
            val imm = terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun getView(): View = terminalView
    override fun dispose() { try { session?.finishIfRunning() } catch (_: Exception) {} }
}
