package io.bluetape4k.aws.kotlin.sfn

import aws.sdk.kotlin.services.sfn.SfnClient
import aws.sdk.kotlin.services.sfn.model.CreateStateMachineRequest
import aws.sdk.kotlin.services.sfn.model.DeleteStateMachineRequest
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionRequest
import aws.sdk.kotlin.services.sfn.model.ExecutionStatus
import aws.sdk.kotlin.services.sfn.model.StateMachineType
import io.bluetape4k.aws.kotlin.sfn.model.startExecutionRequestOf
import io.bluetape4k.aws.kotlin.sfn.model.stopExecutionRequestOf
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.opentest4j.TestAbortedException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class SfnSmokeTest: AbstractSfnTest() {

    private companion object: KLoggingChannel() {
        const val ROLE_ARN = "arn:aws:iam::000000000000:role/issue-313-sfn"
        const val PASS_DEFINITION =
            "{\"StartAt\":\"Pass\",\"States\":{\"Pass\":{\"Type\":\"Pass\",\"End\":true}}}"
        const val WAIT_DEFINITION =
            "{\"StartAt\":\"Wait\",\"States\":{\"Wait\":{\"Type\":\"Wait\",\"Seconds\":30,\"End\":true}}}"

        fun Throwable.isLocalStackUnsupported(): Boolean =
            generateSequence(this) { it.cause }.any { throwable ->
                val text = "${throwable.javaClass.name}: ${throwable.message.orEmpty()}"
                text.contains("NotImplemented", ignoreCase = true) ||
                        Regex("\\b501\\b").containsMatchIn(text)
            }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `Step Functions execution lifecycle is bounded and cleaned up`() = runSuspendIO {
        assumeSfnSupported()

        val stateMachines = mutableListOf<String>()
        val executions = mutableListOf<String>()
        runLifecycleWithCleanup(stateMachines, executions)
    }

    private suspend fun runLifecycleWithCleanup(
        stateMachines: MutableList<String>,
        executions: MutableList<String>,
    ) {
        var primaryFailure: Throwable? = null
        try {
            executeLifecycle(stateMachines, executions)
        } catch (ce: CancellationException) {
            primaryFailure = ce
        } catch (failure: Throwable) {
            primaryFailure = if (failure.isLocalStackUnsupported()) {
                TestAbortedException(
                    "live integration unverified: LocalStack does not support Step Functions: " +
                            failure.javaClass.simpleName,
                    failure,
                )
            } else {
                failure
            }
        }
        val cleanupFailure = withContext(NonCancellable) {
            withTimeoutOrNull(30.seconds) { cleanup(stateMachines, executions) }
        }
        if (primaryFailure != null) {
            cleanupFailure?.let(primaryFailure::addSuppressed)
            throw primaryFailure
        }
        cleanupFailure?.let { throw it }
    }

    private suspend fun executeLifecycle(
        stateMachines: MutableList<String>,
        executions: MutableList<String>,
    ) {
        withTimeout(30.seconds) {
            withTestSfnClient(sfnEmulator) { client ->
                val passMachine = client
                    .createIssue313StateMachine(PASS_DEFINITION, "pass")
                    .also(stateMachines::add)
                val executionArn = client.startExecution(
                    startExecutionRequestOf(passMachine, input = "{\"source\":\"issue-313\"}"),
                ).executionArn.also(executions::add)
                val responses = client.describeExecutionFlow(executionArn).toList()
                log.debug { "responses=$responses" }

                check(responses.lastOrNull()?.status == ExecutionStatus.Succeeded) {
                    "Step Functions pass execution did not succeed"
                }
                check(
                    client.listExecutionsByStateMachine(passMachine).executions
                        .any { it.executionArn == executionArn }) {
                    "Step Functions execution was not returned by ListExecutions"
                }

                val waitMachine = client.createIssue313StateMachine(WAIT_DEFINITION, "wait")
                    .also(stateMachines::add)
                val waitExecutionArn = client.startExecution(
                    startExecutionRequestOf(waitMachine, input = "{}"),
                ).executionArn.orEmpty().also(executions::add)
                client.stopExecution(stopExecutionRequestOf(waitExecutionArn))
            }
        }
    }

    private suspend fun SfnClient.createIssue313StateMachine(
        definition: String,
        label: String,
    ): String =
        createStateMachine(
            CreateStateMachineRequest {
                name = "issue-313-$label-${Uuid.V7.nextIdAsString()}"
                this.definition = definition
                roleArn = ROLE_ARN
                type = StateMachineType.Standard
            },
        ).stateMachineArn

    private suspend fun cleanup(stateMachines: List<String>, executions: List<String>): Throwable? {
        var firstFailure: Throwable? = null
        try {
            withTestSfnClient(sfnEmulator) { client ->
                executions.forEach { executionArn ->
                    runCleanupStep({ failure -> firstFailure = firstFailure ?: failure }) {
                        val status = client.describeExecution(
                            DescribeExecutionRequest { this.executionArn = executionArn },
                        ).status
                        if (status == ExecutionStatus.Running) {
                            client.stopExecution(stopExecutionRequestOf(executionArn))
                        }
                    }
                }
                stateMachines.forEach { stateMachineArn ->
                    runCleanupStep({ failure -> firstFailure = firstFailure ?: failure }) {
                        client.deleteStateMachine(
                            DeleteStateMachineRequest {
                                this.stateMachineArn = stateMachineArn
                            },
                        )
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: Throwable) {
            firstFailure = firstFailure ?: failure
        }
        return firstFailure
    }

    private suspend fun runCleanupStep(
        recordFailure: (Throwable) -> Unit,
        action: suspend () -> Unit,
    ) {
        try {
            action()
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: Throwable) {
            recordFailure(failure)
        }
    }
}
