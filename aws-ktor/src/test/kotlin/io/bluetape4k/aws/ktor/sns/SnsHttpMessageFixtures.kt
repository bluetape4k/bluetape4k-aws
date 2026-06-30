package io.bluetape4k.aws.ktor.sns

internal val subscriptionConfirmationJson: String =
    """
    {
      "Type" : "SubscriptionConfirmation",
      "MessageId" : "165545c9-2a5c-472c-8df2-7ff2be2b3b1b",
      "Token" : "token-1",
      "TopicArn" : "arn:aws:sns:us-east-1:000000000000:orders",
      "Message" : "Confirm this subscription.",
      "SubscribeURL" : "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&Token=token-1",
      "Timestamp" : "2012-04-26T20:45:04.751Z",
      "SignatureVersion" : "2",
      "Signature" : "signature-1",
      "SigningCertURL" : "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem"
    }
    """.trimIndent()

internal val notificationJson: String =
    """
    {
      "Type" : "Notification",
      "MessageId" : "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
      "TopicArn" : "arn:aws:sns:us-east-1:000000000000:orders",
      "Subject" : "Order created",
      "Message" : "{\"orderId\":\"order-1\"}",
      "Timestamp" : "2012-05-02T00:54:06.655Z",
      "SignatureVersion" : "2",
      "Signature" : "signature-2",
      "SigningCertURL" : "https://sns.us-east-1.amazonaws.com/SimpleNotificationService.pem",
      "UnsubscribeURL" : "https://sns.us-east-1.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=sub-1"
    }
    """.trimIndent()
