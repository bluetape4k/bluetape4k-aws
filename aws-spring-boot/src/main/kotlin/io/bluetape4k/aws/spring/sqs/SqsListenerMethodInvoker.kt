package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import software.amazon.awssdk.services.sqs.model.Message
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.kotlinFunction

internal class SqsListenerMethodInvoker(
    private val bean: Any,
    private val method: Method,
    private val messageConverter: SqsMessageConverter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val kotlinFunction: KFunction<*>? = method.kotlinFunction
    private val parameterPlan: ParameterPlan = ParameterPlan.from(method, kotlinFunction)
    private val suspendFunction: Boolean = kotlinFunction?.isSuspend == true

    val manualAcknowledgement: Boolean
        get() = parameterPlan.manualAcknowledgement

    internal val hasListPayload: Boolean
        get() = parameterPlan.hasListPayload

    internal val hasBatchAcknowledgement: Boolean
        get() = parameterPlan.hasBatchAcknowledgement

    internal val hasSingleAcknowledgement: Boolean
        get() = parameterPlan.hasSingleAcknowledgement

    suspend fun invoke(message: SqsReceivedMessage, acknowledgement: SqsAcknowledgement) {
        val arguments = parameterPlan.arguments(message, acknowledgement, messageConverter)
        try {
            if (suspendFunction) {
                withContext(dispatcher) {
                    requireNotNull(kotlinFunction).callSuspend(bean, *arguments)
                }
            } else {
                runInterruptible(dispatcher) {
                    method.isAccessible = true
                    method.invoke(bean, *arguments)
                }
            }
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is CancellationException) {
                throw cause
            }
            throw cause
        } catch (e: CancellationException) {
            throw e
        }
    }

    private data class ParameterPlan(
        val parameters: List<Parameter>,
    ): Serializable {
        val manualAcknowledgement: Boolean =
            parameters.any { it == Parameter.ACKNOWLEDGEMENT }
        val hasListPayload: Boolean = parameters.any { it == Parameter.LIST_PAYLOAD }
        val hasBatchAcknowledgement: Boolean = parameters.any { it == Parameter.BATCH_ACKNOWLEDGEMENT }
        val hasSingleAcknowledgement: Boolean = parameters.any { it == Parameter.ACKNOWLEDGEMENT }

        fun arguments(
            message: SqsReceivedMessage,
            acknowledgement: SqsAcknowledgement,
            converter: SqsMessageConverter,
        ): Array<Any> =
            parameters.map { it.argument(message, acknowledgement, converter) }.toTypedArray()

        companion object {
            fun from(method: Method, kotlinFunction: KFunction<*>?): ParameterPlan {
                val parameterTypes = parameterTypes(method, kotlinFunction)
                require(parameterTypes.isNotEmpty()) {
                    "@SqsListener method must have at least one parameter: ${method.toGenericString()}"
                }
                require(parameterTypes.size <= 2) {
                    "@SqsListener method supports at most two parameters: ${method.toGenericString()}"
                }

                val parameters = parameterTypes.map { Parameter.from(it) }
                require(parameters.count { it == Parameter.ACKNOWLEDGEMENT } <= 1) {
                    "@SqsListener method supports at most one SqsAcknowledgement parameter: ${method.toGenericString()}"
                }
                require(parameters.count { it != Parameter.ACKNOWLEDGEMENT } <= 1) {
                    "@SqsListener method supports at most one message payload parameter: ${method.toGenericString()}"
                }
                return ParameterPlan(parameters)
            }

            private fun parameterTypes(method: Method, kotlinFunction: KFunction<*>?): List<Class<*>> =
                if (kotlinFunction?.isSuspend == true) {
                    kotlinFunction.valueParameters.map { it.type.jvmErasure.java }
                } else {
                    method.parameterTypes.toList()
            }

            private const val serialVersionUID: Long = 1L
        }
    }

    private sealed class Parameter: Serializable {
        data object Body: Parameter()
        data object AwsMessage: Parameter()
        data object ReceivedMessage: Parameter()
        data object Acknowledgement: Parameter()
        data object BatchAcknowledgement: Parameter()
        data object ListPayload: Parameter()
        data class Converted(val targetType: Class<*>): Parameter() {
            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        fun argument(
            message: SqsReceivedMessage,
            acknowledgement: SqsAcknowledgement,
            converter: SqsMessageConverter,
        ): Any =
            when (this) {
                Body            -> message.body
                AwsMessage      -> message.message
                ReceivedMessage -> message
                Acknowledgement -> acknowledgement
                BatchAcknowledgement -> throw IllegalStateException("Batch acknowledgement is not available yet")
                ListPayload      -> converter.convert(message, List::class.java)
                is Converted    -> converter.convert(message, targetType)
            }

        companion object {
            val ACKNOWLEDGEMENT: Parameter = Acknowledgement
            val BATCH_ACKNOWLEDGEMENT: Parameter = BatchAcknowledgement
            val LIST_PAYLOAD: Parameter = ListPayload

            fun from(parameterType: Class<*>): Parameter =
                when (parameterType) {
                    String::class.java -> Body
                    Message::class.java -> AwsMessage
                    SqsReceivedMessage::class.java -> ReceivedMessage
                    SqsAcknowledgement::class.java -> Acknowledgement
                    else -> when {
                        List::class.java.isAssignableFrom(parameterType) -> ListPayload
                        parameterType.simpleName == "SqsBatchAcknowledgement" -> BatchAcknowledgement
                        else -> Converted(parameterType)
                    }
                }
            }
        }
}
