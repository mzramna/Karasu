package karasu.domain.category.interactor

import eu.kanade.tachiyomi.data.database.models.Category
import karasu.domain.category.CategoryRepository
import karasu.domain.operations.OpAction
import karasu.domain.operations.OpEntity
import karasu.domain.operations.OperationLog

class InsertCategories(
    private val categoryRepository: CategoryRepository,
    private val operations: OperationLog? = null,
) {
    suspend fun await(categories: List<Category>) = categoryRepository.insertBulk(categories)
    suspend fun awaitOne(category: Category) = categoryRepository.insert(category).also { id ->
        if (id != null) operations?.record(OpEntity.CATEGORY, OpAction.SET, categoryId = id, name = category.name)
    }
}
