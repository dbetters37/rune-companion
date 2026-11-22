package com.aicontrolcenter.providers.anthropic

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals

internal class JsonBridgeTest {
    @Test
    fun `a round trip through the SDK's JSON keeps key order, numbers and strings`() {
        val original = Json.parseToJsonElement(
            """{"z":1,"a":{"n":null,"t":true,"f":false},"list":[1.5,-0.0,0.1,-2.75,12345678901234567890,9007199254740993,"1.50"],
                "text":"line\nbreak \"quoted\" \\ é 😀","empty":{},"none":[]}""",
        )

        val back = sdkToJson(original.toSdkJson())

        assertEquals(original, back)
        assertEquals(original.toString(), back.toString(), "byte-identical once serialised again")
    }

    @Test
    fun `a decimal a double cannot hold keeps its value`() {
        val precise = Json.parseToJsonElement("""[0.12345678901234567890123]""")

        val back = sdkToJson(precise.toSdkJson()).toString()

        assertEquals("[0.12345678901234567890123]", back)
    }
}
