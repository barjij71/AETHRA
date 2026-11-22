package org.aethra.assistant.permissions

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aethra.assistant.MainActivity
import org.aethra.assistant.R
import org.aethra.assistant.aethra.runtime.AndroidRuntimeViewModel
import org.aethra.core.domain.RiskClass
import org.aethra.core.security.PermissionDecision
import org.aethra.core.security.PermissionRequestState
import org.aethra.core.task.Task
import org.aethra.core.tools.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises the visible Activity UI against its real Android-backed Core broker and policy. */
@RunWith(AndroidJUnit4::class)
class AndroidApprovalComposeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun displaysPendingRequestMetadataWithoutPayloadAndApproveReachesCoreExecution() {
        val h = createPendingOperation("approve")
        compose.waitForIdle()

        compose.onNodeWithText(uiString(R.string.sensitive_request_id, h.task.id)).assertIsDisplayed()
        compose.onNodeWithText(uiString(R.string.sensitive_request_tool, h.tool.id)).assertIsDisplayed()
        compose.onNodeWithText(uiString(R.string.sensitive_request_capabilities, h.tool.requiredCapabilities.single())).assertIsDisplayed()
        compose.onNodeWithText(uiString(R.string.sensitive_request_risk, RiskClass.SENSITIVE.name)).assertIsDisplayed()
        compose.onNodeWithText(h.payloadMarker).assertIsNotDisplayed()
        assertEquals(PermissionRequestState.REQUESTED, requestState(h.task.id))

        compose.onNodeWithText(uiString(R.string.approve)).performClick()
        val result = runBlocking { withTimeout(5_000) { h.dispatch.await() } }

        assertTrue(result.isSuccess)
        assertEquals(h.task.id, h.resumedRequestId.get())
        assertEquals(1, h.executions.get())
        assertEquals(PermissionRequestState.COMPLETED, requestState(h.task.id))
        assertTrue(auditHas(h.task.id, "permission.execution.allowed"))
        compose.onNodeWithText(uiString(R.string.sensitive_request_id, h.task.id)).assertIsNotDisplayed()
        compose.onNodeWithText(uiString(R.string.approve)).assertIsNotDisplayed()
        assertEquals(1, h.executions.get())
    }

    @Test
    fun approvingOneVisibleRequestDoesNotApproveAnotherRequest() {
        val first = createPendingOperation("identity-first")
        val second = createPendingOperation("identity-second")
        compose.waitForIdle()

        compose.onNodeWithText(uiString(R.string.sensitive_request_id, first.task.id)).assertIsDisplayed()
        compose.onNodeWithText(uiString(R.string.sensitive_request_id, second.task.id)).assertIsDisplayed()
        compose.onAllNodesWithText(uiString(R.string.approve))[0].performClick()

        assertTrue(runBlocking { withTimeout(5_000) { first.dispatch.await() } }.isSuccess)
        assertEquals(first.task.id, first.resumedRequestId.get())
        assertEquals(PermissionRequestState.COMPLETED, requestState(first.task.id))
        assertEquals(PermissionRequestState.REQUESTED, requestState(second.task.id))
        assertEquals(1, first.executions.get())
        assertEquals(0, second.executions.get())

        compose.onNodeWithText(uiString(R.string.sensitive_request_id, second.task.id)).assertIsDisplayed()
        compose.onNodeWithText(uiString(R.string.approve)).performClick()
        assertTrue(runBlocking { withTimeout(5_000) { second.dispatch.await() } }.isSuccess)
        assertEquals(second.task.id, second.resumedRequestId.get())
        assertEquals(1, second.executions.get())
    }

    @Test
    fun denyAndCancelAreRecordedAndNeverReachProtectedOperation() {
        for ((action, decision, expected) in listOf(
            Triple(R.string.deny, PermissionDecision.DENY, PermissionRequestState.DENIED),
            Triple(R.string.cancel, PermissionDecision.CANCEL, PermissionRequestState.CANCELLED)
        )) {
            val h = createPendingOperation("${decision.name.lowercase()}-blocked")
            compose.waitForIdle()
            compose.onNodeWithText(uiString(R.string.sensitive_request_id, h.task.id)).assertIsDisplayed()
            compose.onNodeWithText(uiString(action)).performClick()

            val result = runBlocking { withTimeout(5_000) { h.dispatch.await() } }
            assertFalse(result.isSuccess)
            assertEquals(expected, requestState(h.task.id))
            assertEquals(0, h.executions.get())
            assertTrue(auditHas(h.task.id, "permission.${expected.name.lowercase()}"))
            compose.onNodeWithText(uiString(R.string.sensitive_request_id, h.task.id)).assertIsNotDisplayed()
        }
    }

    @Test
    fun pendingRequestSurvivesActivityRecreationAndDecidedRequestDoesNotReturn() {
        val h = createPendingOperation("recreation")
        compose.waitForIdle()
        compose.onNodeWithText(uiString(R.string.sensitive_request_id, h.task.id)).assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText(uiString(R.string.sensitive_request_id, h.task.id)).assertIsDisplayed()
        compose.onNodeWithText(uiString(R.string.deny)).performClick()
        runBlocking { withTimeout(5_000) { h.dispatch.await() } }

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText(uiString(R.string.sensitive_request_id, h.task.id)).assertIsNotDisplayed()
        assertEquals(PermissionRequestState.DENIED, requestState(h.task.id))
        assertEquals(0, h.executions.get())
    }

    private fun createPendingOperation(label: String): Harness {
        val model = ViewModelProvider(compose.activity)[AndroidRuntimeViewModel::class.java]
        val runtime = model.runtime
        val adapters = checkNotNull(runtime.platformAdapters)
        val suffix = label.replace('-', '_')
        val capability = "approval.test.$suffix"
        val tool = Tool(
            id = "approval.test.operation.$suffix",
            description = "Instrumented approval boundary operation",
            category = "test",
            requiredCapabilities = listOf(capability),
            riskClass = RiskClass.SENSITIVE,
            approvalRequired = true
        )
        adapters.toolRegistry.register(tool)
        val task = Task("non-sensitive task label")
        val marker = "PAYLOAD_MUST_NOT_APPEAR_$suffix"
        val args = mapOf("private_argument" to marker)
        val executions = AtomicInteger()
        val resumedRequestId = AtomicReference<String?>(null)
        val initial = runBlocking {
            adapters.executionPolicy.execute(task, tool, null, args) {
                executions.incrementAndGet()
                Result.success(mapOf("executed" to true))
            }
        }
        check(initial.isFailure)
        val dispatch = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            adapters.approvalSurface.awaitDecisionAndResume(task.id, initial) { requestId ->
                resumedRequestId.set(requestId)
                adapters.executionPolicy.execute(task, tool, null, args, approvalRequestId = requestId) {
                    executions.incrementAndGet()
                    Result.success(mapOf("executed" to true))
                }
            }
        }
        return Harness(task, tool, marker, executions, dispatch, resumedRequestId)
    }

    private fun requestState(id: String): PermissionRequestState? = runBlocking {
        checkNotNull(ViewModelProvider(compose.activity)[AndroidRuntimeViewModel::class.java]
            .runtime.platformAdapters).permissionBroker.getRequest(id)?.state
    }

    private fun auditHas(requestId: String, operation: String): Boolean = runBlocking {
        checkNotNull(ViewModelProvider(compose.activity)[AndroidRuntimeViewModel::class.java]
            .runtime.platformAdapters).auditLog.all().any { it.target == requestId && it.operation == operation }
    }

    private fun uiString(resource: Int, vararg arguments: Any): String =
        compose.activity.getString(resource, *arguments)

    private data class Harness(
        val task: Task,
        val tool: Tool,
        val payloadMarker: String,
        val executions: AtomicInteger,
        val dispatch: kotlinx.coroutines.Deferred<Result<Map<String, Any>>>,
        val resumedRequestId: AtomicReference<String?>
    )
}
