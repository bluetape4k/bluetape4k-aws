package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.lang.reflect.GenericArrayType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import software.amazon.awssdk.services.sqs.model.Message
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.KVariance
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
        get() = parameterPlan.hasSingleAcknowledgement

    internal val hasListPayload: Boolean
        get() = parameterPlan.hasListPayload

    internal val hasBatchAcknowledgement: Boolean
        get() = parameterPlan.hasBatchAcknowledgement

    internal val hasSingleAcknowledgement: Boolean
        get() = parameterPlan.hasSingleAcknowledgement

    internal fun validateBatchSignature() {
        parameterPlan.validateBatch()
    }

    suspend fun invoke(message: SqsReceivedMessage, acknowledgement: SqsAcknowledgement) {
        invokeReflectively(parameterPlan.arguments(message, acknowledgement, messageConverter))
    }

    internal suspend fun invokeBatch(
        messages: List<SqsReceivedMessage>,
        acknowledgement: SqsBatchAcknowledgement?,
    ) {
        parameterPlan.validateBatch()
        invokeReflectively(parameterPlan.batchArguments(messages, acknowledgement, messageConverter))
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun invokeReflectively(arguments: Array<Any>) {
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

    private data class ParameterSpec(
        val name: String?,
        val rawType: Class<*>,
        val kType: KType?,
        val genericType: Type?,
    )

    private data class ParameterPlan(
        val parameters: List<Parameter>,
    ): Serializable {
        val hasListPayload: Boolean = parameters.any { it is Parameter.ListPayload }
        val hasBatchAcknowledgement: Boolean = parameters.any { it is Parameter.BatchAcknowledgement }
        val hasSingleAcknowledgement: Boolean = parameters.any { it is Parameter.Acknowledgement }

        private val payloads: List<Parameter> = parameters.filter { it.isPayload }

        fun arguments(
            message: SqsReceivedMessage,
            acknowledgement: SqsAcknowledgement,
            converter: SqsMessageConverter,
        ): Array<Any> =
            parameters.map { it.argument(message, acknowledgement, converter) }.toTypedArray()

        fun batchArguments(
            messages: List<SqsReceivedMessage>,
            acknowledgement: SqsBatchAcknowledgement?,
            converter: SqsMessageConverter,
        ): Array<Any> =
            parameters.map { it.batchArgument(messages, acknowledgement, converter) }.toTypedArray()

        fun validateBatch() {
            require(payloads.size == 1 && payloads.single() is Parameter.ListPayload) {
                "batch=true requires a List payload"
            }
            parameters.filterIsInstance<Parameter.ListPayload>().forEach { it.resolveElementType() }
        }

        companion object {
            fun from(method: Method, kotlinFunction: KFunction<*>?): ParameterPlan {
                val specs = parameterSpecs(method, kotlinFunction)
                require(specs.isNotEmpty()) {
                    "@SqsListener method must have at least one parameter: ${method.toGenericString()}"
                }
                require(specs.size <= 2) {
                    "@SqsListener method supports at most two parameters: ${method.toGenericString()}"
                }

                val parameters = specs.map { Parameter.from(it) }
                val acknowledgementCount = parameters.count {
                    it is Parameter.Acknowledgement || it is Parameter.BatchAcknowledgement
                }
                require(acknowledgementCount <= 1) {
                    "@SqsListener method supports at most one acknowledgement parameter: ${method.toGenericString()}"
                }
                require(parameters.count { it.isPayload } <= 1) {
                    "@SqsListener method supports at most one message payload parameter: ${method.toGenericString()}"
                }
                return ParameterPlan(parameters)
            }

            private fun parameterSpecs(method: Method, kotlinFunction: KFunction<*>?): List<ParameterSpec> =
                if (kotlinFunction != null) {
                    kotlinFunction.valueParameters.map { parameter ->
                        ParameterSpec(
                            name = parameter.name,
                            rawType = parameter.type.jvmErasure.java,
                            kType = parameter.type,
                            genericType = null,
                        )
                    }
                } else {
                    method.genericParameterTypes.mapIndexed { index, type ->
                        ParameterSpec(
                            name = method.parameters.getOrNull(index)?.name,
                            rawType = rawClass(type),
                            kType = null,
                            genericType = type,
                        )
                    }
                }

            private fun rawClass(type: Type): Class<*> = when (type) {
                is Class<*> -> type
                is ParameterizedType -> rawClass(type.rawType)
                is GenericArrayType -> Array<Any>::class.java
                else -> Any::class.java
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

        class ListPayload(
            private val name: String?,
            private val kType: KType?,
            private val genericType: Type?,
        ): Parameter() {
            private var elementType: Class<*>? = null

            fun resolveElementType(): Class<*> {
                elementType?.let { return it }
                val resolved = when {
                    kType != null -> resolveKType(kType)
                    genericType != null -> resolveJavaType(genericType)
                    else -> unsupported("raw List payload is not supported")
                }
                elementType = resolved
                return resolved
            }

            @Suppress("TooGenericExceptionCaught")
            fun elementArguments(
                messages: List<SqsReceivedMessage>,
                converter: SqsMessageConverter,
            ): List<Any> {
                val targetType = resolveElementType()
                return when (targetType) {
                    SqsReceivedMessage::class.java -> messages
                    Message::class.java -> messages.map { it.message }
                    else -> messages.mapIndexed { index, message ->
                        try {
                            converter.convert(message, targetType)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            throw SqsMessageConversionException(index, targetType, e)
                        }
                    }
                }
            }

            private fun resolveKType(type: KType): Class<*> {
                val argument = type.arguments.singleOrNull()?.type
                    ?: unsupported("raw List payload is not supported")
                if (type.arguments.single().variance != KVariance.INVARIANT || argument.isMarkedNullable) {
                    return unsupported(argument.toString())
                }
                val classifier = argument.classifier as? KClass<*>
                    ?: unsupported(argument.toString())
                val target = classifier.java
                requireSupportedElement(target, argument.toString())
                return target
            }

            private fun resolveJavaType(type: Type): Class<*> {
                val parameterized = type as? ParameterizedType
                    ?: unsupported("raw List payload is not supported")
                val argument = parameterized.actualTypeArguments.singleOrNull()
                    ?: unsupported(parameterized.typeName)
                val target = when (argument) {
                    is Class<*> -> argument
                    else -> unsupported(argument.typeName)
                }
                requireSupportedElement(target, argument.typeName)
                return target
            }

            private fun requireSupportedElement(target: Class<*>, description: String) {
                require(target != List::class.java && !List::class.java.isAssignableFrom(target)) {
                    unsupported(description)
                }
                require(target != Any::class.java &&
                    target != Serializable::class.java) {
                    unsupported(description)
                }
                require(!target.isInterface && !Modifier.isAbstract(target.modifiers)) {
                    unsupported(description)
                }
            }

            private fun unsupported(description: String): Nothing =
                throw IllegalArgumentException(
                    "unsupported batch element type for parameter ${name ?: "<unnamed>"}: $description"
                )

            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        data class Converted(val targetType: Class<*>): Parameter() {
            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        val isPayload: Boolean
            get() = this !is Acknowledgement && this !is BatchAcknowledgement

        fun argument(
            message: SqsReceivedMessage,
            acknowledgement: SqsAcknowledgement,
            converter: SqsMessageConverter,
        ): Any =
            when (this) {
                Body -> message.body
                AwsMessage -> message.message
                ReceivedMessage -> message
                Acknowledgement -> acknowledgement
                BatchAcknowledgement -> throw IllegalArgumentException("SqsBatchAcknowledgement requires batch=true")
                is ListPayload -> throw IllegalArgumentException("batch=false does not accept List payload")
                is Converted -> converter.convert(message, targetType)
            }

        fun batchArgument(
            messages: List<SqsReceivedMessage>,
            acknowledgement: SqsBatchAcknowledgement?,
            converter: SqsMessageConverter,
        ): Any =
            when (this) {
                is ListPayload -> elementArguments(messages, converter)
                BatchAcknowledgement -> requireNotNull(acknowledgement) {
                    "MANUAL requires SqsBatchAcknowledgement"
                }
                else -> throw IllegalArgumentException("batch=true requires a List payload")
            }

        companion object {
            fun from(spec: ParameterSpec): Parameter = when (spec.rawType) {
                String::class.java -> Body
                Message::class.java -> AwsMessage
                SqsReceivedMessage::class.java -> ReceivedMessage
                SqsAcknowledgement::class.java -> Acknowledgement
                SqsBatchAcknowledgement::class.java -> BatchAcknowledgement
                else -> if (List::class.java.isAssignableFrom(spec.rawType)) {
                    ListPayload(spec.name, spec.kType, spec.genericType)
                } else {
                    Converted(spec.rawType)
                }
            }
        }
    }
}
