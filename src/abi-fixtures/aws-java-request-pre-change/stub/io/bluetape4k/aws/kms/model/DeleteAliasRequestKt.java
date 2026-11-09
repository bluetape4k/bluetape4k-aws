package io.bluetape4k.aws.kms.model;

import software.amazon.awssdk.services.kms.model.DeleteAliasRequest;

public final class DeleteAliasRequestKt {
    private DeleteAliasRequestKt() {}

    public static DeleteAliasRequest deleteAliasOf(String aliasName) {
        return DeleteAliasRequest.builder().aliasName(aliasName).build();
    }
}
