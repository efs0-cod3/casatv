package com.casa.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The remote's arrows, OK, Back and Home where ADB is out of reach (Fire OS 7.6+ closes ADB
 * connections that come from the TV itself). Enabled once, from a computer:
 *   adb shell settings put secure enabled_accessibility_services com.casa.tv/com.casa.tv.RemoteService
 *   adb shell settings put secure accessibility_enabled 1
 */
class RemoteService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteService? = null
    }

    override fun onServiceConnected() {
        instance = this
        CasaService.capsChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        CasaService.capsChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun key(name: String): Boolean = when (name) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "sleep" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "up" -> move(View.FOCUS_UP)
        "down" -> move(View.FOCUS_DOWN)
        "left" -> move(View.FOCUS_LEFT)
        "right" -> move(View.FOCUS_RIGHT)
        "ok" -> click()
        else -> false
    }

    /** Moves the D-pad focus the way the arrow keys would. */
    private fun move(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val current = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return firstFocusable(root)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
        val next = current.focusSearch(direction) ?: return false
        return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun click(): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        var node: AccessibilityNodeInfo? = focused
        while (node != null && !node.isClickable) node = node.parent
        return (node ?: focused).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun firstFocusable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocusable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { firstFocusable(it) }
            if (found != null) return found
        }
        return null
    }
}
