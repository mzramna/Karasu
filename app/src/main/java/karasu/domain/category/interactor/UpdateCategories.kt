package karasu.domain.category.interactor

import eu.kanade.tachiyomi.data.database.models.Category
import karasu.domain.category.CategoryRepository
import karasu.domain.category.models.CategoryUpdate
import karasu.domain.operations.OpAction
import karasu.domain.operations.OpEntity
import karasu.domain.operations.OperationLog

class UpdateCategories(
    private val categoryRepository: CategoryRepository,
    private val operations: OperationLog? = null,
) {
    suspend fun await(updates: List<CategoryUpdate>) = categoryRepository.updateAll(updates).also { recordRenames(updates) }

    suspend fun awaitOne(update: CategoryUpdate) = categoryRepository.update(update).also { recordRenames(listOf(update)) }

    /** Only a new name is something the sync follows; an order or a flag is local taste. */
    private suspend fun recordRenames(updates: List<CategoryUpdate>) {
        updates.filter { it.name != null }.forEach {
            operations?.record(OpEntity.CATEGORY, OpAction.SET, categoryId = it.id, name = it.name)
        }
    }
}
