package org.aethra.assistant.aethra.runtime

import android.content.Context
import androidx.lifecycle.ViewModel
import org.aethra.assistant.aethra.permissions.AndroidApprovalSurface
import org.aethra.assistant.aethra.permissions.AndroidApprovalUi

/** Retains the in-memory Core request journal across Activity recreation. */
internal class AndroidRuntimeViewModel(context: Context) : ViewModel() {
    val runtime: Runtime = createRuntime(context.applicationContext).also { it.start() }

    private val approvalCoordinator: AndroidApprovalSurface by lazy {
        val adapters = checkNotNull(runtime.platformAdapters) { "Runtime is not initialized" }
        adapters.approvalSurface
    }
    val approvalUi: AndroidApprovalUi get() = approvalCoordinator

    override fun onCleared() {
        approvalCoordinator.close()
        runtime.shutdown()
        super.onCleared()
    }
}
