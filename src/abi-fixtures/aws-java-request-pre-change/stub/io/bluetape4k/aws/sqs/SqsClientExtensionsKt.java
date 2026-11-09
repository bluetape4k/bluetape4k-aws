package io.bluetape4k.aws.sqs;

public final class SqsClientExtensionsKt {
    private SqsClientExtensionsKt() {}

    public static void validateReceiveMessageCount(int maxResults) {
        if (maxResults < 1 || maxResults > 10) {
            throw new IllegalArgumentException("maxResults must be in 1..10");
        }
    }
}
