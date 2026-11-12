package com.nishith.usageguard

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.window.OnBackInvokedDispatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * A full-screen dark window drawn over the limited app the moment its limit is hit.
 *
 * Two jobs:
 *  1. Android 15+ only lets an app with "Display over other apps" open a screen from the
 *     background while one of its overlay windows is visible. Showing this first is what
 *     lets the full lock screen (LockActivity) open reliably.
 *  2. If Android still refuses to open the lock screen, this stays up as the lock itself,
 *     so the app underneath can't be used.
 */
class LockShield(private val ctx: Context, private val onGoHome: () -> Unit) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private lateinit var title: TextView
    private lateinit var clock: TextView
    private lateinit var sub: TextView

    val isShowing get() = root != null

    fun show(info: LockInfo) {
        if (root == null) {
            val view = build()
            runCatching { wm.addView(view, params()) }.onFailure { return }
            root = view
            // Android 13+ (and by default when targeting Android 16) delivers Back through
            // OnBackInvokedDispatcher instead of key events, so listen there as well.
            if (Build.VERSION.SDK_INT >= 33) {
                view.post {
                    runCatching {
                        view.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        ) { onGoHome() }
                    }
                }
            }
        }
        title.text = "${info.title} is cooling down"
        val opens = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(info.until))
        sub.text = "${info.lockedApps} ${if (info.plural) "open" else "opens"} again at $opens"
        clock.text = formatClock(info.until - System.currentTimeMillis())
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
    ).toInt()

    private fun build(): View {
        val muted = Color.parseColor("#C9CCD3")
        val frame = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onGoHome()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#0E0F12"))
            isClickable = true // swallow touches so the app underneath can't be used
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), 0, dp(24f), 0)
        }
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        col.addView(text(12f, muted).apply { text = "COOLDOWN"; letterSpacing = 0.1f })
        clock = text(52f, Color.WHITE, bold = true)
        col.addView(clock)
        title = text(24f, Color.WHITE, bold = true)
        col.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20f) })
        sub = text(15f, muted)
        col.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        frame.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        val button = text(16f, Color.parseColor("#16181D"), bold = true).apply {
            text = "Go to home screen"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F07A2F")); cornerRadius = dp(16f).toFloat()
            }
            isClickable = true
            setOnClickListener { onGoHome() }
        }
        frame.addView(
            button,
            FrameLayout.LayoutParams(-1, dp(56f), Gravity.BOTTOM).apply {
                setMargins(dp(24f), 0, dp(24f), dp(48f))
            },
        )
        return frame
    }
}
