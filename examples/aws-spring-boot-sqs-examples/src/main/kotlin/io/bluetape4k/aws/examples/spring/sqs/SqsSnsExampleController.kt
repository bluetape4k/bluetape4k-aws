package io.bluetape4k.aws.examples.spring.sqs

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/spring/sqs")
class SqsSnsExampleController(
    private val service: SqsSnsExampleService,
    private val receivedOrderStore: ReceivedOrderStore,
) {

    @PostMapping("/queues/{queueName}")
    suspend fun createQueue(@PathVariable queueName: String): QueueResponse =
        service.createQueue(queueName)

    @PostMapping("/messages")
    suspend fun send(
        @RequestParam("queue") queueNameOrUrl: String,
        @RequestBody request: SendQueueMessageRequest,
    ): QueueSendResponse =
        service.send(queueNameOrUrl, request)

    @GetMapping("/messages")
    suspend fun receive(
        @RequestParam("queue") queueNameOrUrl: String,
        @RequestParam(defaultValue = "false") deleteAfterReceive: Boolean,
    ): List<QueueMessageResponse> =
        service.receive(queueNameOrUrl, deleteAfterReceive)

    @PostMapping("/fanout")
    suspend fun createFanout(@RequestBody request: FanoutSetupRequest): FanoutSetupResponse =
        service.createFanout(request)

    @PostMapping("/topics/messages")
    suspend fun publish(@RequestBody request: PublishTopicMessageRequest): TopicPublishResponse =
        service.publish(request)

    @PostMapping("/dlq")
    suspend fun createDlqPair(@RequestBody request: DlqSetupRequest): DlqSetupResponse =
        service.createDlqPair(request)

    @GetMapping("/listener/messages")
    fun listenerMessages(): List<String> =
        receivedOrderStore.recent()
}
