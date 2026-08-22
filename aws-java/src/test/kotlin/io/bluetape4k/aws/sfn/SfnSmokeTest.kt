package io.bluetape4k.aws.sfn

import io.bluetape4k.aws.sfn.model.startExecutionRequestOf
import io.bluetape4k.aws.sfn.model.stopExecutionRequestOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.opentest4j.TestAbortedException
import software.amazon.awssdk.services.sfn.model.CreateStateMachineRequest
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest
import software.amazon.awssdk.services.sfn.model.ExecutionStatus
import software.amazon.awssdk.services.sfn.model.StateMachineType
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
                withSfnAsyncClient(
                    endpoint = sfnEmulator.awsEndpoint,
                    region = sfnEmulator.region(),
                    credentialsProvider = sfnEmulator.credentialsProvider,
                ) { client ->
                    val passMachine = client.createStateMachine(
                        CreateStateMachineRequest.builder()
                            .name("issue-313-pass-${UUID.randomUUID()}")
                            .definition(PASS_DEFINITION)
                            .roleArn(ROLE_ARN)
                            .type(StateMachineType.STANDARD)
                            .build(),
                    ).await().stateMachineArn().also(stateMachines::add)

                    val executionArn = client.startExecution(
                        startExecutionRequestOf(passMachine, input = "{\"source\":\"issue-313\"}"),
                    ).await().executionArn().also(executions::add)

                    val responses = client.describeExecutionFlow(executionArn).toList()
                    check(responses.lastOrNull()?.status() == ExecutionStatus.SUCCEEDED) {
                        "Step Functions pass execution did not succeed"
                    }

                    val listed = client.listExecutionsByStateMachine(passMachine)
                    check(listed.executions().any { it.executionArn() == executionArn }) {
                        "Step Functions execution was not returned by ListExecutions"
                    }

                    val waitMachine = client.createStateMachine(
                        CreateStateMachineRequest.builder()
                            .name("issue-313-wait-${UUID.randomUUID()}")
                            .definition(WAIT_DEFINITION)
                            .roleArn(ROLE_ARN)
                            .type(StateMachineType.STANDARD)
                            .build(),
                    ).await().stateMachineArn().also(stateMachines::add)

                    val waitExecutionArn = client.startExecution(
                        startExecutionRequestOf(waitMachine, input = "{}"),
                    ).await().executionArn().also(executions::add)
                    client.stopExecution(stopExecutionRequestOf(waitExecutionArn)).await()
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
            withSfnAsyncClient(
                endpoint = sfnEmulator.awsEndpoint,
                region = sfnEmulator.region(),
                credentialsProvider = sfnEmulator.credentialsProvider,
            ) { client ->
                executions.forEach { executionArn ->
                    runCatching {
                        val status = client.describeExecution(
                            DescribeExecutionRequest.builder().executionArn(executionArn).build(),
                        ).await().status()
                        if (status == ExecutionStatus.RUNNING) {
                            client.stopExecution(stopExecutionRequestOf(executionArn)).await()
                        }
                    }.onFailure { firstFailure = firstFailure ?: it }
                }
                stateMachines.forEach { stateMachineArn ->
                    runCatching { client.deleteStateMachine { it.stateMachineArn(stateMachineArn) } }
                        .onFailure { firstFailure = firstFailure ?: it }
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
