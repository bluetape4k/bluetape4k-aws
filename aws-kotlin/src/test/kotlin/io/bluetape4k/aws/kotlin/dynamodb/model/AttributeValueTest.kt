package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.io.extractBytes
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class AttributeValueTest {

    companion object: KLogging()

    @Test
    fun `null값은 AttributeValue Null로 변환된다`() {
        val av = null.toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.Null>()
        av.value.shouldBeTrue()
    }

    @Test
    fun `String은 AttributeValue S로 변환된다`() {
        val av = "hello".toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.S>()
        av.value shouldBeEqualTo "hello"
    }

    @Test
    fun `Int는 AttributeValue N으로 변환된다`() {
        val av = 42.toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.N>()
        av.value shouldBeEqualTo "42"
    }

    @Test
    fun `Long은 AttributeValue N으로 변환된다`() {
        val av = 123456789L.toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.N>()
        av.value shouldBeEqualTo "123456789"
    }

    @Test
    fun `Double은 AttributeValue N으로 변환된다`() {
        val av = 3.14.toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.N>()
        av.value shouldBeEqualTo "3.14"
    }

    @Test
    fun `Boolean은 AttributeValue Bool로 변환된다`() {
        val avTrue = true.toAttributeValue()
        avTrue.shouldBeInstanceOf<AttributeValue.Bool>()
        avTrue.value.shouldBeTrue()

        val avFalse = false.toAttributeValue()
        avFalse.shouldBeInstanceOf<AttributeValue.Bool>()
        avFalse.value.shouldBeFalse()
    }

    @Test
    fun `ByteArray는 AttributeValue B로 변환된다`() {
        val bytes = byteArrayOf(1, 2, 3)
        val av = bytes.toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.B>()
        av.value shouldBeEqualTo bytes
    }

    @Test
    fun `ByteBuffer는 AttributeValue B로 변환된다`() {
        val buf = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        val av = buf.toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.B>()
        av.value shouldBeEqualTo buf.extractBytes()
    }

    @Test
    fun `String List는 AttributeValue Ss로 변환된다`() {
        // List<String> → Iterable<CharSequence> 오버로드 → AttributeValue.Ss (String Set)
        val list = listOf("a", "b", "c")
        val av = list.toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.Ss>()
        av.value shouldBeEqualTo list
    }

    @Test
    fun `혼합 List는 AttributeValue L로 변환된다`() {
        // 혼합 타입 목록은 AttributeValue.L (List)
        val mixed: List<Any> = listOf("a", 1, true)
        val av = mixed.toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.L>()
        av.value.size shouldBeEqualTo mixed.size
    }

    @Test
    fun `Map은 AttributeValue M으로 변환된다`() {
        val map = mapOf("name" to "Alice", "age" to 30)
        val av = map.toAttributeValue()
        av.shouldBeInstanceOf<AttributeValue.M>()
        av.value["name"] shouldBeEqualTo AttributeValue.S("Alice")
        av.value["age"] shouldBeEqualTo AttributeValue.N("30")
    }

    @Test
    fun `toAttributeValueList는 Iterable 요소를 AttributeValue 목록으로 변환한다`() {
        val items = listOf("x", "y", "z")
        val avList = items.toAttributeValueList()

        avList.size shouldBeEqualTo 3
        avList[0] shouldBeEqualTo AttributeValue.S("x")
        avList[1] shouldBeEqualTo AttributeValue.S("y")
        avList[2] shouldBeEqualTo AttributeValue.S("z")
    }

    @Test
    fun `toAttributeValueMap는 Map을 String-AttributeValue 맵으로 변환한다`() {
        val map = mapOf("id" to "u1", "score" to 100)
        val avMap = map.toAttributeValueMap()

        avMap["id"] shouldBeEqualTo AttributeValue.S("u1")
        avMap["score"] shouldBeEqualTo AttributeValue.N("100")
    }

    @Test
    fun `AttributeValue 자신은 그대로 반환된다`() {
        val original = AttributeValue.S("test")
        val result = original.toAttributeValue()
        result shouldBeEqualTo original
    }
}
