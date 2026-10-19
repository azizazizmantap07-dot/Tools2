package com.flashing.tool.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.WindowCompat
import com.flashing.tool.R
import com.google.android.material.button.MaterialButton

/**
 * Liquid glass styling without covering content.
 *
 * - Styles all buttons with glass drawables
 * - Optional window background blur (API 31+, device-dependent)
 * - Does NOT inject full-screen views or children into LinearLayout/ScrollView
 *   (that previously hid menus)
 */
object GlassUi {

    fun install(activity: Activity) {
        try {
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        } catch (_: Throwable) {
        }

        // Soft system window blur when the OEM supports it (never covers app content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                activity.window.setBackgroundBlurRadius(40)
            } catch (_: Throwable) {
            }
        }

        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val page = contentRoot.getChildAt(0) ?: return
        styleAllButtons(page)
    }

    private fun styleAllButtons(root: View) {
        when (root) {
            is MaterialButton -> styleMaterial(root)
            is Button -> styleLegacy(root)
            is ViewGroup -> {
                for (i in 0 until root.childCount) {
                    styleAllButtons(root.getChildAt(i))
                }
            }
        }
    }

    private fun styleMaterial(btn: MaterialButton) {
        if (btn.tag == "glass_styled") return
        btn.tag = "glass_styled"
        try {
            btn.backgroundTintList = null
        } catch (_: Throwable) {
        }
        val text = (btn.text?.toString() ?: "").lowercase()
        val danger = listOf("hapus", "stop", "putus", "erase", "clear", "batal").any { text.contains(it) }
        val primary = listOf(
            "hubung", "jalankan", "connect", "mulai", "run", "flash",
            "backup", "write", "start", "force",
        ).any { text.contains(it) }
        val drawable = when {
            danger -> R.drawable.bg_button_glass_danger
            primary -> R.drawable.bg_button_glass
            else -> R.drawable.bg_button_glass_tonal
        }
        try {
            btn.setBackgroundResource(drawable)
        } catch (_: Throwable) {
        }
    }

    private fun styleLegacy(btn: Button) {
        if (btn is MaterialButton) return
        if (btn.tag == "glass_styled") return
        btn.tag = "glass_styled"
        val text = (btn.text?.toString() ?: "").lowercase()
        val primary = listOf("backup", "flash", "write", "jalankan", "mulai", "run", "select").any {
            text.contains(it)
        }
        try {
            btn.setBackgroundResource(
                if (primary) R.drawable.bg_button_glass else R.drawable.bg_button_glass_tonal,
            )
        } catch (_: Throwable) {
        }
    }
}
