package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.jvm.jvmErasure
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import software.amazon.awssdk.services.sqs.model.Message

internal class SqsListenerMethodInvoker(
    private val bean: Any,
    private val method: Method,
    private val messageConverter: SqsMessageConverter,
) {
    private val kotlinFunction: KFunction<*>? = method.kotlinFunction
    private val parameterPlan: ParameterPlan = ParameterPlan.from(method, kotlinFunction)
    private val suspendFunction: Boolean = kotlinFunction?.isSuspend == true

    val manualAcknowledgement: Boolean
        get() = parameterPlan.manualAcknowledgement

    suspend fun invoke(message: SqsReceivedMessage, acknowledgement: SqsAcknowledgement) {
        val arguments = parameterPlan.arguments(message, acknowledgement, messageConverter)
        try {
            withContext(Dispatchers.IO) {
                if (suspendFunction) {
                    requireNotNull(kotlinFunction).callSuspend(bean, *arguments)
                } else {
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
    ) {
        val manualAcknowledgement: Boolean =
            parameters.any { it == Parameter.ACKNOWLEDGEMENT }

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
        }
    }

    private sealed class Parameter {
        data object Body: Parameter()
        data object AwsMessage: Parameter()
        data object ReceivedMessage: Parameter()
        data object Acknowledgement: Parameter()
        data class Converted(val targetType: Class<*>): Parameter()

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
                is Converted    -> converter.convert(message, targetType)
            }

        companion object {
            val ACKNOWLEDGEMENT: Parameter = Acknowledgement

            fun from(parameterType: Class<*>): Parameter =
                when (parameterType) {
                    String::class.java -> Body
                    Message::class.java -> AwsMessage
                    SqsReceivedMessage::class.java -> ReceivedMessage
                    SqsAcknowledgement::class.java -> Acknowledgement
                    else -> Converted(parameterType)
                }
            }
        }
}
