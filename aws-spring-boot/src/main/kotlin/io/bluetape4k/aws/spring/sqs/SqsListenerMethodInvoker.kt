package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.jvm.jvmErasure
import software.amazon.awssdk.services.sqs.model.Message
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal class SqsListenerMethodInvoker(
    private val bean: Any,
    private val method: Method,
) {
    private val kotlinFunction: KFunction<*>? = method.kotlinFunction
    private val parameterKind: ParameterKind = ParameterKind.from(method, kotlinFunction)
    private val suspendFunction: Boolean = kotlinFunction?.isSuspend == true

    suspend fun invoke(message: SqsReceivedMessage) {
        val argument = parameterKind.argument(message)
        try {
            withContext(Dispatchers.IO) {
                if (suspendFunction) {
                    requireNotNull(kotlinFunction).callSuspend(bean, argument)
                } else {
                    method.isAccessible = true
                    method.invoke(bean, argument)
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

    private enum class ParameterKind {
        BODY,
        MESSAGE,
        RECEIVED;

        fun argument(message: SqsReceivedMessage): Any =
            when (this) {
                BODY     -> message.body
                MESSAGE  -> message.message
                RECEIVED -> message
            }

        companion object {
            fun from(method: Method, kotlinFunction: KFunction<*>?): ParameterKind {
                val parameterType = if (kotlinFunction?.isSuspend == true) {
                    val parameters = kotlinFunction.valueParameters
                    require(parameters.size == 1) {
                        "@SqsListener method must have exactly one parameter: ${method.toGenericString()}"
                    }
                    parameters.single().type.jvmErasure.java
                } else {
                    val parameters = method.parameterTypes
                    require(parameters.size == 1) {
                        "@SqsListener method must have exactly one parameter: ${method.toGenericString()}"
                    }
                    parameters.single()
                }

                return when (parameterType) {
                    String::class.java -> BODY
                    Message::class.java -> MESSAGE
                    SqsReceivedMessage::class.java -> RECEIVED
                    else -> throw IllegalArgumentException(
                        "Unsupported @SqsListener parameter type: ${parameterType.name} on ${method.toGenericString()}"
                    )
                }
            }
        }
    }
}
