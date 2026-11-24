package karasu.domain.extension.repo.interactor

import karasu.domain.extension.repo.ExtensionRepoRepository
import karasu.domain.operations.OpAction
import karasu.domain.operations.OpEntity
import karasu.domain.operations.OperationLog

class DeleteExtensionRepo(
    private val extensionRepoRepository: ExtensionRepoRepository,
    private val operations: OperationLog? = null,
) {
    suspend fun await(baseUrl: String) {
        extensionRepoRepository.deleteRepository(baseUrl)
        operations?.record(OpEntity.EXTENSION_REPO, OpAction.REMOVE, name = baseUrl)
    }
}
