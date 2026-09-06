package com.godviewer.app.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.View
import androidx.core.view.drawToBitmap

/**
 * 安全截取 View 预览/缩略图。
 *
 * 部分目标应用的 SurfaceView / TextureView / 未布局 / 超大视图上，
 * 直接 [View.drawToBitmap] 会抛异常导致进程闪退；这里统一兜底。
 */
object ViewSnapshot {
    private const val TAG = "GodViewer.Snap"
    private const val DEFAULT_MAX_EDGE = 512
    private const val MAX_PIXELS = 2_000_000

    fun capture(view: View?, maxEdge: Int = DEFAULT_MAX_EDGE): Bitmap? {
        if (view == null) return null
        if (view.visibility == View.GONE) return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return runCatching { captureInternal(view, maxEdge) }
            .onFailure { Log.w(TAG, "capture failed view=${view.javaClass.name}", it) }
            .getOrNull()
    }

    private fun captureInternal(view: View, maxEdge: Int): Bitmap {
        // 1) 优先 AndroidX drawToBitmap（已布局且软件层较稳）
        val raw = runCatching {
            if (view.isLaidOut) view.drawToBitmap() else null
        }.getOrNull()
        if (raw != null && !raw.isRecycled) {
            return scaleDown(raw, maxEdge)
        }
        // 2) 手动 Canvas 绘制（部分硬件加速视图 drawToBitmap 会失败）
        val sw = view.width.coerceAtLeast(1)
        val sh = view.height.coerceAtLeast(1)
        val scale = computeScale(sw, sh, maxEdge)
        val bw = (sw * scale).toInt().coerceAtLeast(1)
        val bh = (sh * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        if (scale != 1f) {
            canvas.scale(scale, scale)
        }
        runCatching {
            view.draw(canvas)
        }.onFailure {
            // 个别 View.draw 会抛；尝试只画背景
            view.background?.draw(canvas)
        }
        return bitmap
    }

    fun scaleDown(bitmap: Bitmap, maxEdge: Int = DEFAULT_MAX_EDGE): Bitmap {
        if (bitmap.isRecycled) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxEdge && height <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / maxOf(width, height).toFloat()
        val nw = (width * scale).toInt().coerceAtLeast(1)
        val nh = (height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        }.getOrDefault(bitmap)
    }

    private fun computeScale(w: Int, h: Int, maxEdge: Int): Float {
        val edge = maxOf(w, h).toFloat().coerceAtLeast(1f)
        var scale = 1f
        if (edge > maxEdge) {
            scale = maxEdge / edge
        }
        val pixels = w.toLong() * h.toLong()
        if (pixels * scale * scale > MAX_PIXELS) {
            scale = kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels.toDouble()).toFloat()
        }
        return scale.coerceIn(0.05f, 1f)
    }

    /** 列表占位：1x1 透明，避免 Glide/ImageView 空引用路径 */
    fun placeholder(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}
