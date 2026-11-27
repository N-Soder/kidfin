// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import au.kidfin.tv.ui.AppContainer

class KidFinApplication : Application(), SingletonImageLoader.Factory {
    internal val container: AppContainer by lazy { AppContainer(this) }

    /** Bounded artwork caches. Image URLs carry no token; auth goes in a header. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(64L * 1024 * 1024).build() }
        .build()
}
