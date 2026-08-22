package io.bluetape4k.aws.kotlin.sfn

import aws.sdk.kotlin.services.sfn.model.CreateStateMachineRequest
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionRequest
import aws.sdk.kotlin.services.sfn.model.DeleteStateMachineRequest
import aws.sdk.kotlin.services.sfn.model.ExecutionStatus
import aws.sdk.kotlin.services.sfn.model.StateMachineType
import io.bluetape4k.aws.kotlin.sfn.model.startExecutionRequestOf
import io.bluetape4k.aws.kotlin.sfn.model.stopExecutionRequestOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.opentest4j.TestAbortedException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class SfnSmokeTest : AbstractSfnTest() {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `Step Functions execution lifecycle is bounded and cleaned up`() = runBlocking {
        assumeSfnSupported()

        val stateMachines = mutableListOf<String>()
        val executions = mutableListOf<String>()
        var primaryFailure: Throwable? = null

        try {
            withTimeout(30.seconds) {
                withSfnClient(
                    endpointUrl = sfnEmulator.endpointUrl,
                    region = sfnEmulator.region,
                    credentialsProvider = sfnEmulator.credentialsProvider,
                ) { client ->
                    val passMachine = client.createStateMachine(
                        CreateStateMachineRequest {
                            name = "issue-313-pass-${UUID.randomUUID()}"
                            definition = PASS_DEFINITION
                            roleArn = ROLE_ARN
                            type = StateMachineType.Standard
                        },
                    ).stateMachineArn.orEmpty().also(stateMachines::add)

                    val executionArn = client.startExecution(
                        startExecutionRequestOf(passMachine, input = "{\"source\":\"issue-313\"}"),
                    ).executionArn.orEmpty().also(executions::add)

                    val responses = client.describeExecutionFlow(executionArn).toList()
                    check(responses.lastOrNull()?.status == ExecutionStatus.Succeeded) {
                        "Step Functions pass execution did not succeed"
                    }

                    val listed = client.listExecutionsByStateMachine(passMachine)
                    check(listed.executions.orEmpty().any { it.executionArn == executionArn }) {
                        "Step Functions execution was not returned by ListExecutions"
                    }

                    val waitMachine = client.createStateMachine(
                        CreateStateMachineRequest {
                            name = "issue-313-wait-${UUID.randomUUID()}"
                            definition = WAIT_DEFINITION
                            roleArn = ROLE_ARN
                            type = StateMachineType.Standard
                        },
                    ).stateMachineArn.orEmpty().also(stateMachines::add)

                    val waitExecutionArn = client.startExecution(
                        startExecutionRequestOf(waitMachine, input = "{}"),
                    ).executionArn.orEmpty().also(executions::add)
                    client.stopExecution(stopExecutionRequestOf(waitExecutionArn))
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            if (failure.isLocalStackUnsupported()) {
                throw TestAbortedException(
                    "live integration unverified: LocalStack does not support Step Functions: " +
                        failure.javaClass.simpleName,
                    failure,
                )
            }
            throw failure
        } finally {
            val cleanupFailure = withContext(NonCancellable) {
                withTimeoutOrNull(30.seconds) {
                    cleanup(stateMachines, executions)
                }
            }
            if (cleanupFailure != null && primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure)
            } else if (cleanupFailure != null) {
                throw cleanupFailure
            }
        }
        Unit
    }

    private suspend fun cleanup(stateMachines: List<String>, executions: List<String>): Throwable? {
        var firstFailure: Throwable? = null
        runCatching {
            withSfnClient(
                endpointUrl = sfnEmulator.endpointUrl,
                region = sfnEmulator.region,
                credentialsProvider = sfnEmulator.credentialsProvider,
            ) { client ->
                executions.forEach { executionArn ->
                    runCatching {
                        val status = client.describeExecution(
                            DescribeExecutionRequest { this.executionArn = executionArn },
                        ).status
                        if (status == ExecutionStatus.Running) {
                            client.stopExecution(stopExecutionRequestOf(executionArn))
                        }
                    }.onFailure { firstFailure = firstFailure ?: it }
                }
                stateMachines.forEach { stateMachineArn ->
                    runCatching {
                        client.deleteStateMachine(
                            DeleteStateMachineRequest {
                                this.stateMachineArn = stateMachineArn
                            },
                        )
                    }.onFailure { firstFailure = firstFailure ?: it }
                }
            }
        }.onFailure { firstFailure = firstFailure ?: it }
        return firstFailure
    }

    private companion object {
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
}
