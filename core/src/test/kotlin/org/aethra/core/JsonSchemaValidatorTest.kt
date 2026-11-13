package org.aethra.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.aethra.core.skills.JsonSchemaValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonSchemaValidatorTest {
    private val schema = Json.parseToJsonElement(
        """{"type":"object","required":["name","count"],"properties":{"name":{"type":"string","minLength":2},"count":{"type":"integer","minimum":1}},"additionalProperties":false}"""
    ).jsonObject

    @Test
    fun acceptsPayloadMatchingDeclaredContract() {
        val payload = Json.parseToJsonElement("""{"name":"AETHRA","count":3}""")
        assertTrue(JsonSchemaValidator.validate(payload, schema).isEmpty())
    }

    @Test
    fun rejectsMissingRequiredAndWrongType() {
        val missing = Json.parseToJsonElement("""{"name":"AETHRA"}""")
        assertTrue(JsonSchemaValidator.validate(missing, schema).any { it.contains("missing required property") })

        val wrongType = Json.parseToJsonElement("""{"name":"AETHRA","count":"three"}""")
        assertTrue(JsonSchemaValidator.validate(wrongType, schema).any { it.contains("expected integer") })
    }

    @Test
    fun rejectsUndeclaredProperties() {
        val payload = Json.parseToJsonElement("""{"name":"AETHRA","count":3,"admin":true}""")
        assertTrue(JsonSchemaValidator.validate(payload, schema).any { it.contains("additional property") })
    }

    @Test
    fun rejectsUnsupportedKeywordsInsteadOfIgnoringThem() {
        val unsupported = Json.parseToJsonElement(
            """{"type":"object","unsupportedValidationKeyword":true}"""
        ).jsonObject
        assertFalse(JsonSchemaValidator.validateSchema(unsupported).isEmpty())
    }

    @Test
    fun rejectsMalformedRegularExpression() {
        val invalid = Json.parseToJsonElement("""{"type":"string","pattern":"["}""").jsonObject
        assertFalse(JsonSchemaValidator.validateSchema(invalid).isEmpty())
    }

    @Test
    fun enforcesArrayItemSchemasAndUniqueness() {
        val arraySchema = Json.parseToJsonElement(
            """{"type":"array","items":{"type":"integer"},"uniqueItems":true,"minItems":1}"""
        ).jsonObject
        val payload = Json.parseToJsonElement("""[1,1,"two"]""")
        val errors = JsonSchemaValidator.validate(payload, arraySchema)
        assertTrue(errors.any { it.contains("unique") })
        assertTrue(errors.any { it.contains("expected integer") })
    }
}
