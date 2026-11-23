package karasu.domain.category.interactor

import eu.kanade.tachiyomi.data.database.models.MangaCategory
import karasu.domain.category.CategoryRepository
import karasu.domain.manga.MangaRepository
import karasu.domain.operations.OpAction
import karasu.domain.operations.OpEntity
import karasu.domain.operations.OperationLog

class SetMangaCategories(
    private val mangaRepository: MangaRepository,
    private val categoryRepository: CategoryRepository? = null,
    private val operations: OperationLog? = null,
) {
    suspend fun await(mangaId: Long?, categories: List<Long>) {
        val id = mangaId ?: return
        // What changed, not what is: the sync follows links being added and taken away.
        val before = if (operations != null) {
            categoryRepository?.getAllByMangaId(id)?.mapNotNullTo(mutableSetOf()) { it.id?.toLong() }
        } else {
            null
        }
        mangaRepository.setCategories(id, categories)
        if (before != null) {
            val after = categories.toSet()
            (after - before).forEach { operations?.record(OpEntity.CATEGORY_LINK, OpAction.SET, mangaId = id, categoryId = it) }
            (before - after).forEach { operations?.record(OpEntity.CATEGORY_LINK, OpAction.REMOVE, mangaId = id, categoryId = it) }
        }
    }
    suspend fun awaitAll(mangaIds: List<Long>, mangaCategories: List<MangaCategory>) =
        mangaRepository.setMultipleMangaCategories(mangaIds, mangaCategories)
}
