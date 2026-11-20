package com.naua_security_mirage.app.ui.view

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.naua_security_mirage.app.R

object CherryBadgeHelper {
    enum class Type { NEW, FIX }

    fun createBadge(context: Context, type: Type): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 9.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(
                (6f * density).toInt(),
                (1.5f * density).toInt(),
                (6f * density).toInt(),
                (1.5f * density).toInt()
            )
            when (type) {
                Type.NEW -> {
                    text = "NEW"
                    background = ContextCompat.getDrawable(context, R.drawable.bg_badge_pill_new)
                }
                Type.FIX -> {
                    text = "FIX"
                    background = ContextCompat.getDrawable(context, R.drawable.bg_badge_pill_fix)
                }
            }
        }
    }

    /**
     * Highlights `[NEW]` with blue tag and `[FIX]` with green tag in changelog text.
     */
    fun formatChangelog(rawText: String?): CharSequence {
        if (rawText.isNullOrBlank()) return ""
        val ssb = SpannableStringBuilder(rawText)
        val text = ssb.toString()

        val newRegex = Regex("""\[NEW\]|\bNEW\b""")
        for (match in newRegex.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            ssb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#2AABEE")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val fixRegex = Regex("""\[FIX\]|\bFIX\b""")
        for (match in fixRegex.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            ssb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#27AE60")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return ssb
    }
}
