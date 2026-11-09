package io.bluetape4k.aws.kotlin.s3tables.model;

import java.util.List;
import aws.sdk.kotlin.services.s3tables.model.GetTableRequest;
import aws.sdk.kotlin.services.s3tables.model.ListNamespacesRequest;
import aws.sdk.kotlin.services.s3tables.model.ListTableBucketsRequest;
import aws.sdk.kotlin.services.s3tables.model.ListTablesRequest;

public final class S3TablesRequestSupportKt {
    private S3TablesRequestSupportKt() {
    }

    public static void requireSelector(String value, String field) {
    }

    public static void requireNamespace(List<String> value) {
    }

    public static void validateList(ListTableBucketsRequest request, Integer value, String field) {
    }

    public static void validateList(ListNamespacesRequest request, Integer value, String field) {
    }

    public static void validateList(ListTablesRequest request, Integer value, String field) {
    }

    public static void validatePage(Integer value, String field) {
    }

    public static void requireTableLookup(GetTableRequest request) {
    }
}
