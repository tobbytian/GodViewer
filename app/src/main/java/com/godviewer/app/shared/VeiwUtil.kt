package com.godviewer.app.shared

import android.content.res.Resources

fun Float.px(): Float {
    val scale = Resources.getSystem().displayMetrics.density
    return this * scale + 0.5F
}

fun Float.dp(): Float {
    val scale = Resources.getSystem().displayMetrics.density
    return this / scale + 0.5F
}

fun Int.px(): Int {
    val scale = Resources.getSystem().displayMetrics.density
    return (this * scale + 0.5F).toInt()
}

fun Int.dp(): Int {
    val scale = Resources.getSystem().displayMetrics.density
    return (this / scale + 0.5F).toInt()
}
