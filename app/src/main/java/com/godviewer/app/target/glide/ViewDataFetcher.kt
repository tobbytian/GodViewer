package com.godviewer.app.target.glide

import android.graphics.Bitmap
import android.view.View
import com.godviewer.app.shared.ViewSnapshot
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher

class ViewDataFetcher(private val view: View) : DataFetcher<Bitmap> {
    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        try {
            val bitmap = ViewSnapshot.capture(view)
            if (bitmap != null) {
                callback.onDataReady(bitmap)
            } else {
                callback.onLoadFailed(IllegalStateException("view snapshot unavailable"))
            }
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
    }

    override fun cancel() {
    }

    override fun getDataClass(): Class<Bitmap> = Bitmap::class.java

    override fun getDataSource(): DataSource = DataSource.MEMORY_CACHE
}
