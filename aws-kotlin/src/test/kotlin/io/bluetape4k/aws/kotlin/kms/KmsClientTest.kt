package io.bluetape4k.aws.kotlin.kms

import aws.sdk.kotlin.services.kms.createAlias
import aws.sdk.kotlin.services.kms.createGrant
import aws.sdk.kotlin.services.kms.createKey
import aws.sdk.kotlin.services.kms.decrypt
import aws.sdk.kotlin.services.kms.deleteAlias
import aws.sdk.kotlin.services.kms.describeKey
import aws.sdk.kotlin.services.kms.disableKey
import aws.sdk.kotlin.services.kms.enableKey
import aws.sdk.kotlin.services.kms.encrypt
import aws.sdk.kotlin.services.kms.listAliases
import aws.sdk.kotlin.services.kms.listGrants
import aws.sdk.kotlin.services.kms.listKeys
import aws.sdk.kotlin.services.kms.model.GrantOperation
import aws.sdk.kotlin.services.kms.model.KeySpec
import aws.sdk.kotlin.services.kms.model.KeyUsageType
import aws.sdk.kotlin.services.kms.putKeyPolicy
import aws.sdk.kotlin.services.kms.revokeGrant
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KmsClientTest: AbstractKmsTest() {

    companion object: KLoggingChannel()

    private val testKeyDescription = "예제용 KMS 키에 대한 설명입니다 - By KmsClient"
    private lateinit var testKeyId: String

    private val data = randomString()
    private lateinit var cyphertextBlob: ByteArray

    // LocalStack 테스트 환경에서는 빈 문자열로도 Grant 생성이 가능합니다.
    private val testGranteePrincipal = "debop"
    private lateinit var testGrantId: String

    // alias 는 prefix로 "alias/" 를 써야합니다.
    private val testAliasName = "alias/CustomAliasNameByKmsClient"

    @Test
    @Order(1)
    fun `KmsClient 인스턴스 생성 테스트`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            with(client.config) {
                log.debug { "endpointUrl=$endpointUrl" }
                log.debug { "region=$region" }
                log.debug { "clientName=$clientName" }
                log.debug { "applicationId=$applicationId" }
            }
        }
    }

    @Test
    @Order(2)
    fun `대칭 키 생성`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            val response = client.createKey {
                keySpec = KeySpec.SymmetricDefault
                keyUsage = KeyUsageType.EncryptDecrypt
                description = testKeyDescription
            }
            log.debug { "created key: $response" }
            response.keyMetadata?.keySpec shouldBeEqualTo KeySpec.SymmetricDefault
            response.keyMetadata?.keyUsage shouldBeEqualTo KeyUsageType.EncryptDecrypt

            testKeyId = response.keyMetadata?.keyId.shouldNotBeEmpty()
            log.info { "custom keyId=$testKeyId" }
        }
    }

    @Test
    @Order(3)
    fun `데이터 암호화`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            val response = client.encrypt {
                keyId = testKeyId
                plaintext = data.toUtf8Bytes()
            }
            log.debug { "encrypted keyId=$testKeyId" }

            val algorithm = response.encryptionAlgorithm.toString().shouldNotBeEmpty()
            log.debug { "Encryption algorithm: $algorithm" }

            cyphertextBlob = response.ciphertextBlob.shouldNotBeNull()
        }
    }

    @Test
    @Order(4)
    fun `데이터 복호화`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            val response = client.decrypt {
                this.keyId = testKeyId
                this.ciphertextBlob = cyphertextBlob
            }
            log.debug { "decrypt response=$response" }

            val plainBytes = response.plaintext.shouldNotBeNull()
            plainBytes.toUtf8String() shouldBeEqualTo data
        }
    }

    @Test
    @Order(5)
    fun `키 비활성화`() = runSuspendIO {
        //assumeFlociSupports("KMS DisableKey")

        withTestKmsClient(awsEmulator) { client ->
            val response = client.disableKey {
                keyId = testKeyId
            }
            log.debug { "disableKey response=$response" }
        }
    }

    @Test
    @Order(6)
    fun `키 활성화`() = runSuspendIO {
        // assumeFlociSupports("KMS EnableKey")

        withTestKmsClient(awsEmulator) { client ->
            val response = client.enableKey {
                keyId = testKeyId
            }
            log.debug { "enableKey response=$response" }
        }
    }

    @Test
    @Order(7)
    fun `Grant 생성`() = runSuspendIO {
        // assumeFlociSupports("KMS CreateGrant")

        withTestKmsClient(awsEmulator) { client ->
            val response = client.createGrant {
                keyId = testKeyId
                granteePrincipal = testGranteePrincipal
                operations = listOf(GrantOperation.CreateGrant, GrantOperation.Encrypt, GrantOperation.Decrypt)
            }

            log.debug { "Grant id=${response.grantId}, token=${response.grantToken}" }
            testGrantId = response.grantId.shouldNotBeNull()
        }
    }

    @Test
    @Order(8)
    fun `Grant 목록 조회`() = runSuspendIO {
        // assumeFlociSupports("KMS ListGrants")

        withTestKmsClient(awsEmulator) { client ->
            val listGrantsResponse = client.listGrants {
                keyId = testKeyId
                limit = 15
            }
            log.debug { "listGrants response=$listGrantsResponse" }

            val grants = listGrantsResponse.grants.shouldNotBeEmpty()
            grants.forEach { grant -> log.debug { "Grant id=${grant.grantId}" } }
            grants.map { it.grantId } shouldContain testGrantId
        }
    }

    @Test
    @Order(9)
    fun `Grant 취소`() = runSuspendIO {
        // assumeFlociSupports("KMS RevokeGrant")

        withTestKmsClient(awsEmulator) { client ->
            val response = client.revokeGrant {
                keyId = testKeyId
                grantId = testGrantId
            }
            log.debug { "revokeGrant response=$response" }
        }
    }

    @Test
    @Order(10)
    fun `키 메타데이터 조회`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            val response = client.describeKey {
                keyId = testKeyId
            }
            log.debug { "describeKey response=$response" }

            val keyMetadata = response.keyMetadata.shouldNotBeNull()
            log.debug { "key metadata=$keyMetadata" }
            log.debug { "key description=${keyMetadata.description}" }
            log.debug { "key id=${keyMetadata.keyId}, arn=${keyMetadata.arn}" }
            keyMetadata.description shouldBeEqualTo testKeyDescription
        }
    }

    @Test
    @Order(11)
    fun `커스텀 Alias 생성`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            log.debug { "Create custom alias. alias name=${testAliasName}, keyId=$testKeyId" }

            val response = client.createAlias {
                targetKeyId = testKeyId
                aliasName = testAliasName
            }

            log.debug { "createAlias response=$response" }
        }
    }

    @Test
    @Order(12)
    fun `Alias 목록 조회`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            val response = client.listAliases { limit = 15 }
            log.debug { "listAlias response=$response" }

            val aliases = response.aliases.shouldNotBeNull()
            aliases.forEach { log.debug { "alias=$it" } }
            aliases.map { it.aliasName } shouldContain testAliasName
        }
    }

    @Test
    @Order(13)
    fun `Alias 삭제`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            val response = client.deleteAlias {
                aliasName = testAliasName
            }
            log.debug { "deleteAlias response=$response" }
        }
    }

    @Test
    @Order(14)
    fun `키 목록 조회`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            val response = client.listKeys { limit = 15 }

            val keys = response.keys.shouldNotBeNull()
            keys.forEach { log.debug { "key=$it" } }
            keys.shouldNotBeEmpty().map { it.keyId } shouldContain testKeyId
        }
    }

    @Test
    @Order(15)
    fun `키 정책 설정`() = runSuspendIO {
        withTestKmsClient(awsEmulator) { client ->
            val testPolicyName = "default"
            val testPolicy = """
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                            "Effect": "Allow",
                            "Principal": {"AWS": "arn:aws:iam::814548047983:root"},
                            "Action": "kms:*",
                            "Resource": "*"
                        }
                    ]
                }""".trimIndent()

            val response = client.putKeyPolicy {
                keyId = testKeyId
                policyName = testPolicyName
                policy = testPolicy
            }
            log.debug { "putKeyPolicy response=$response" }
            response.shouldNotBeNull()
        }
    }
}
