package com.aidotble.widget.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue

/** Tiny programmatic-view helpers so the activities stay dependency-light (no XML, no Material). */

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    val p = dp(16)
    setPadding(p, p, p, p)
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}

fun Context.text(s: CharSequence, sizeSp: Float = 16f, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

fun Context.button(label: CharSequence, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    setOnClickListener { onClick() }
}

fun Context.checkbox(label: CharSequence, checked: Boolean): CheckBox = CheckBox(this).apply {
    text = label
    isChecked = checked
}

fun Context.input(initial: String, hint: String = ""): EditText = EditText(this).apply {
    setText(initial)
    this.hint = hint
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}

fun LinearLayout.add(v: View): LinearLayout { addView(v); return this }

/** "20, 50, 100" <-> listOf(20,50,100). */
fun List<Int>.toCsv(): String = joinToString(", ")
fun String.toIntListOrNull(): List<Int>? = split(",")
    .map { it.trim() }.filter { it.isNotEmpty() }
    .map { it.toIntOrNull() ?: return null }
    .takeIf { it.isNotEmpty() }

/** RGB presets as packed 0xRRGGBB ints <-> "#ff0000, #00aaff". Empty string -> empty list. */
fun List<Int>.toHexCsv(): String = joinToString(", ") { "#%06X".format(it and 0xFFFFFF) }
fun String.toRgbListOrEmpty(): List<Int>? {
    val parts = split(",").map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
    val out = ArrayList<Int>(parts.size)
    for (p in parts) {
        if (p.length != 6) return null
        out.add(p.toIntOrNull(16) ?: return null)
    }
    return out // may be empty (RGB presets are optional)
}
