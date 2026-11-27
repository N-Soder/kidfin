// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.jellyfin

import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaLibrary
import au.kidfin.tv.media.MediaPage
import au.kidfin.tv.media.MediaRepository
import au.kidfin.tv.media.MediaType
import au.kidfin.tv.media.MediaUnavailableException
import au.kidfin.tv.media.ShelfContent
import au.kidfin.tv.media.ShelfOrdering
import au.kidfin.tv.media.ShelfSource
import au.kidfin.tv.media.SeriesEpisodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/**
 * Media from the dedicated child account. Every query is scoped to one parent-approved library
 * (parentId) as that user, so the server's own permissions are the content boundary and the
 * library allow-list is a second layer. Only playable leaf items are requested.
 */
class JellyfinMediaRepository(private val connection: JellyfinConnection) : MediaRepository, ShelfSource {

    override suspend fun libraries(): List<MediaLibrary> = guarded { connection.videoLibraries() }

    override suspend fun page(libraryIds: Set<String>, startIndex: Int, limit: Int): MediaPage = guarded {
        connection.call { api, config ->
            val allowed = libraryIds.intersect(config.libraryIds).sorted()
            LibraryPager.page(
                libraries = allowed,
                startIndex = startIndex,
                limit = limit,
                count = { lib -> query(api, config.userId, lib, 0, 1).totalRecordCount },
                fetch = { lib, start, n ->
                    query(api, config.userId, lib, start, n).items.map { toMediaItem(api, it, lib) }
                },
            ).let { MediaPage(it.items, it.totalCount) }
        }
    }

    /**
     * One round per library, all requests in parallel and unsorted (ordering happens on the
     * TV): titles with play counts, resumable items, next-up episodes and played episodes
     * (for per-series play counts). Each is scoped to the library as the child account.
     */
    override suspend fun shelf(libraryIds: Set<String>): ShelfContent = guarded {
        connection.call { api, config ->
            val user = config.userId.toUUID()
            val libs = libraryIds.intersect(config.libraryIds).sorted()
            coroutineScope {
                val perLibrary = libs.map { lib ->
                    async {
                        val parent = lib.toUUID()
                        val titles = async {
                            api.libraryApi.getItems(
                                userId = user,
                                parentId = parent,
                                recursive = true,
                                includeItemTypes = SHELF_TITLES,
                                excludeLocationTypes = listOf(LocationType.VIRTUAL),
                                enableUserData = true,
                                imageTypeLimit = 1,
                                enableImageTypes = IMAGE_TYPES,
                                enableTotalRecordCount = false,
                            ).content.items
                        }
                        val resume = async {
                            api.libraryApi.getResumeItems(
                                userId = user,
                                parentId = parent,
                                limit = ShelfOrdering.ROW,
                                includeItemTypes = PLAYABLE,
                                enableUserData = true,
                                imageTypeLimit = 1,
                                enableImageTypes = IMAGE_TYPES,
                                enableTotalRecordCount = false,
                            ).content.items
                        }
                        val nextUp = async {
                            api.showApi.getNextUp(
                                userId = user,
                                parentId = parent,
                                limit = ShelfOrdering.ROW,
                                enableUserData = true,
                                imageTypeLimit = 1,
                                enableImageTypes = IMAGE_TYPES,
                                enableTotalRecordCount = false,
                                enableResumable = false,
                                enableRewatching = false,
                            ).content.items
                        }
                        val played = async {
                            api.libraryApi.getItems(
                                userId = user,
                                parentId = parent,
                                recursive = true,
                                includeItemTypes = listOf(BaseItemKind.EPISODE),
                                filters = listOf(ItemFilter.IS_PLAYED),
                                enableUserData = true,
                                enableImages = false,
                                enableTotalRecordCount = false,
                            ).content.items
                        }
                        val seriesPlays = played.await()
                            .groupBy { it.seriesId?.toString() }
                            .mapValues { (_, eps) -> eps.sumOf { it.userData?.playCount ?: 0 } }
                        Triple(
                            titles.await().map { dto ->
                                val item = toMediaItem(api, dto, lib)
                                if (dto.type == BaseItemKind.SERIES) item.copy(plays = seriesPlays[item.id] ?: 0) else item
                            },
                            resume.await().map { toMediaItem(api, it, lib) },
                            nextUp.await().map { toMediaItem(api, it, lib) },
                        )
                    }
                }.awaitAll()
                ShelfContent(
                    keepWatching = perLibrary.flatMap { it.second } + perLibrary.flatMap { it.third },
                    titles = perLibrary.flatMap { it.first },
                )
            }
        }
    }

    override suspend fun nextEpisode(series: MediaItem): MediaItem? = guarded {
        if (series.type != MediaType.SERIES || series.libraryId !in connection.allowedLibraryIds) return@guarded null
        val seriesId = series.id.toUUIDOrNull() ?: return@guarded null
        connection.call { api, config ->
            val user = config.userId.toUUID()
            // The caller's library id is only a hint: confirm with the server where the show lives.
            val (_, library) = locate(api, config, seriesId, SERIES) ?: return@call null
            val next = api.showApi.getNextUp(
                userId = user,
                seriesId = seriesId,
                limit = 1,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = IMAGE_TYPES,
                enableResumable = true,
                enableRewatching = true,
            ).content.items.firstOrNull()
            val dto = next ?: api.showApi.getEpisodes(
                seriesId = seriesId,
                userId = user,
                limit = 1,
                enableUserData = true,
                enableImages = true,
                imageTypeLimit = 1,
                enableImageTypes = IMAGE_TYPES,
                sortBy = ItemSortBy.PARENT_INDEX_NUMBER,
            ).content.items.firstOrNull()
            dto?.let { toMediaItem(api, it, library) }
        }
    }

    override suspend fun episodes(series: MediaItem): SeriesEpisodes = guarded {
        if (series.type != MediaType.SERIES || series.libraryId !in connection.allowedLibraryIds) {
            throw MediaUnavailableException("not permitted")
        }
        val seriesId = series.id.toUUIDOrNull() ?: throw MediaUnavailableException("not permitted")
        connection.call { api, config ->
            val user = config.userId.toUUID()
            // The caller's library id is only a hint: confirm with the server where the show lives.
            val (_, library) = locate(api, config, seriesId, SERIES) ?: throw MediaUnavailableException("not permitted")
            coroutineScope {
                val all = async {
                    api.showApi.getEpisodes(
                        seriesId = seriesId,
                        userId = user,
                        enableUserData = true,
                        enableImages = true,
                        imageTypeLimit = 1,
                        enableImageTypes = IMAGE_TYPES,
                    ).content.items
                }
                val next = async {
                    api.showApi.getNextUp(
                        userId = user,
                        seriesId = seriesId,
                        limit = 1,
                        enableImages = false,
                        enableResumable = true,
                        enableRewatching = true,
                    ).content.items.firstOrNull()?.id?.toString()
                }
                SeriesEpisodes(
                    episodes = all.await()
                        .filter { it.locationType != LocationType.VIRTUAL }
                        .map { toMediaItem(api, it, library) },
                    nextUpId = next.await(),
                )
            }
        }
    }

    override suspend fun setFavourite(item: MediaItem, favourite: Boolean) = guarded {
        if (item.libraryId !in connection.allowedLibraryIds) throw MediaUnavailableException("not permitted")
        // A series is hearted as a whole, never a single episode.
        val target = (item.seriesId ?: item.id).toUUIDOrNull() ?: throw MediaUnavailableException("not permitted")
        connection.call { api, config ->
            val user = config.userId.toUUID()
            locate(api, config, target, SHELF_TITLES + PLAYABLE) ?: throw MediaUnavailableException("not permitted")
            if (favourite) api.userDataApi.markFavoriteItem(target, user) else api.userDataApi.unmarkFavoriteItem(target, user)
            Unit
        }
    }

    override suspend fun item(itemId: String): MediaItem? = guarded {
        val id = itemId.toUUIDOrNull() ?: return@guarded null
        connection.call { api, config ->
            locate(api, config, id, PLAYABLE)?.let { (dto, lib) -> toMediaItem(api, dto, lib) }
        }
    }

    /**
     * Finds [id] (one of [types]) for the child account inside an approved library, or null.
     * The server scopes the query to the library (parentId, recursive) and must return exactly
     * this item; anything unexpected fails closed. The item's ancestor list is not used: on real
     * servers it holds physical folders, not the library's id, so it refused permitted shows.
     */
    private suspend fun locate(api: ApiClient, config: ServerConfig, id: UUID, types: List<BaseItemKind>): Pair<BaseItemDto, String>? {
        val user = config.userId.toUUID()
        for (lib in config.libraryIds.sorted()) {
            val dto = api.libraryApi.getItems(
                userId = user,
                parentId = lib.toUUID(),
                recursive = true,
                ids = listOf(id),
                includeItemTypes = types,
                limit = 1,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = IMAGE_TYPES,
            ).content.items.firstOrNull() ?: continue
            return if (dto.id != id || dto.type !in types) null else dto to lib
        }
        return null
    }

    private suspend fun query(api: ApiClient, userId: String, library: String, start: Int, limit: Int) =
        api.libraryApi.getItems(
            userId = userId.toUUID(),
            parentId = library.toUUID(),
            recursive = true,
            includeItemTypes = PLAYABLE,
            excludeLocationTypes = listOf(LocationType.VIRTUAL),
            sortBy = listOf(ItemSortBy.SERIES_SORT_NAME, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            startIndex = start,
            limit = limit,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = IMAGE_TYPES,
            enableTotalRecordCount = true,
        ).content

    private fun toMediaItem(api: ApiClient, dto: BaseItemDto, libraryId: String): MediaItem = MediaItem(
        id = dto.id.toString(),
        title = dto.name ?: "Untitled",
        type = when (dto.type) {
            BaseItemKind.MOVIE -> MediaType.MOVIE
            BaseItemKind.EPISODE -> MediaType.EPISODE
            BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> MediaType.VIDEO
            BaseItemKind.SERIES -> MediaType.SERIES
            else -> MediaType.OTHER
        },
        // Item-level runtime only. Never the series runtime.
        runtimeTicks = dto.runTimeTicks,
        libraryId = libraryId,
        seriesName = dto.seriesName,
        imageUrl = wideImage(api, dto),
        posterUrl = poster(api, dto),
        year = dto.productionYear,
        plays = dto.userData?.playCount ?: 0,
        seriesId = (if (dto.type == BaseItemKind.SERIES) dto.id else dto.seriesId)?.toString(),
        favourite = dto.userData?.isFavorite == true,
        season = dto.parentIndexNumber,
        episode = dto.indexNumber,
        watched = dto.userData?.played == true,
        progress = dto.userData?.playedPercentage?.takeIf { it in 1.0..97.0 }?.let { (it / 100).toFloat() },
        resumeTicks = dto.userData?.playbackPositionTicks
            ?.takeIf { it > 0 && (dto.userData?.playedPercentage ?: 0.0) in 1.0..97.0 },
    )

    /** Portrait poster: the item's own, or the series poster for an episode. */
    private fun poster(api: ApiClient, dto: BaseItemDto): String? {
        fun url(id: java.util.UUID, tag: String) =
            api.imageApi.getItemImageUrl(itemId = id, imageType = ImageType.PRIMARY, maxWidth = POSTER_WIDTH, tag = tag, quality = 85)
        if (dto.type == BaseItemKind.EPISODE) {
            val series = dto.seriesId
            val tag = dto.seriesPrimaryImageTag
            return if (series != null && tag != null) url(series, tag) else null
        }
        return dto.imageTags?.get(ImageType.PRIMARY)?.let { url(dto.id, it) }
    }

    /** Landscape art for the cards: thumb, backdrop, episode still, parent backdrop, poster. */
    private fun wideImage(api: ApiClient, dto: BaseItemDto): String? {
        val tags = dto.imageTags.orEmpty()
        fun url(id: java.util.UUID, type: ImageType, tag: String?) =
            api.imageApi.getItemImageUrl(itemId = id, imageType = type, maxWidth = IMAGE_WIDTH, tag = tag, quality = 85)
        tags[ImageType.THUMB]?.let { return url(dto.id, ImageType.THUMB, it) }
        dto.backdropImageTags?.firstOrNull()?.let { return url(dto.id, ImageType.BACKDROP, it) }
        if (dto.type == BaseItemKind.EPISODE) tags[ImageType.PRIMARY]?.let { return url(dto.id, ImageType.PRIMARY, it) }
        val parent = dto.parentBackdropItemId
        val parentTag = dto.parentBackdropImageTags?.firstOrNull()
        if (parent != null && parentTag != null) return url(parent, ImageType.BACKDROP, parentTag)
        return tags[ImageType.PRIMARY]?.let { url(dto.id, ImageType.PRIMARY, it) }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MediaUnavailableException) {
        throw e
    } catch (e: Exception) {
        // Messages stay generic: never echo server text or URLs into the child UI or logs.
        throw MediaUnavailableException(if (e is NotConnectedException) "not connected" else "server unavailable", e)
    }

    private companion object {
        val PLAYABLE = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO)
        val SHELF_TITLES = listOf(BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.SERIES)
        val SERIES = listOf(BaseItemKind.SERIES)
        val IMAGE_TYPES = listOf(ImageType.THUMB, ImageType.BACKDROP, ImageType.PRIMARY)
        const val IMAGE_WIDTH = 640
        const val POSTER_WIDTH = 400
    }
}

/**
 * Presents several libraries as one stable list (libraries in id order, server order within
 * each) for the index-based [MediaRepository.page] contract.
 */
internal object LibraryPager {
    suspend fun <T> page(
        libraries: List<String>,
        startIndex: Int,
        limit: Int,
        count: suspend (String) -> Int,
        fetch: suspend (library: String, start: Int, limit: Int) -> List<T>,
    ): PagedResult<T> {
        val counts = libraries.map { it to count(it).coerceAtLeast(0) }
        val total = counts.sumOf { it.second }
        val out = ArrayList<T>()
        var offset = 0
        var cursor = startIndex.coerceAtLeast(0)
        for ((lib, n) in counts) {
            if (out.size >= limit) break
            if (cursor < offset + n) {
                val localStart = cursor - offset
                val got = fetch(lib, localStart, minOf(limit - out.size, n - localStart))
                out += got
                cursor += got.size
                if (got.isEmpty()) break
            }
            offset += n
        }
        return PagedResult(out, total)
    }

    data class PagedResult<T>(val items: List<T>, val totalCount: Int)
}
