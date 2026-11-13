package org.aethra.core.skills

import kotlinx.serialization.json.*

/**
 * Fail-closed validator for the JSON Schema subset currently used by AETHRA skill contracts.
 *
 * Supported validation keywords: type, properties, required, additionalProperties, items,
 * enum, const, pattern, minLength, maxLength, minimum, maximum, minItems, maxItems and
 * uniqueItems. Metadata keywords are accepted but do not affect validation. Unsupported
 * keywords (including external $ref) are rejected rather than silently ignored.
 */
object JsonSchemaValidator {
    private val types = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val validationKeywords = setOf(
        "type", "properties", "required", "additionalProperties", "items",
        "enum", "const", "pattern", "minLength", "maxLength",
        "minimum", "maximum", "minItems", "maxItems", "uniqueItems"
    )
    private val metadataKeywords = setOf(
        "$" + "schema", "$" + "id", "$" + "comment",
        "title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly"
    )

    fun validateSchema(schema: JsonObject): List<String> = buildList {
        validateSchemaObject(schema, "$", this)
    }

    fun validate(value: JsonElement, schema: JsonObject): List<String> {
        val schemaErrors = validateSchema(schema)
        if (schemaErrors.isNotEmpty()) {
            return schemaErrors.map { "invalid schema: " + it }
        }
        return buildList {
            validateValue(value, schema, "$", this)
        }
    }

    private fun validateSchemaObject(schema: JsonObject, path: String, errors: MutableList<String>) {
        schema.keys.filterNot { it in validationKeywords || it in metadataKeywords }
            .forEach { errors += path + ": unsupported keyword '" + it + "'" }

        val typeElement = schema["type"]
        if (typeElement != null) {
            val type = typeElement as? JsonPrimitive
            if (type == null || !type.isString || type.content !in types) {
                errors += path + ": type must be one supported JSON type"
            }
        }

        val propertiesElement = schema["properties"]
        if (propertiesElement != null) {
            val properties = propertiesElement as? JsonObject
            if (properties == null) {
                errors += path + ": properties must be an object"
            } else {
                properties.forEach { (name, child) ->
                    val childSchema = child as? JsonObject
                    if (childSchema == null) {
                        errors += path + ".properties." + name + ": schema must be an object"
                    } else {
                        validateSchemaObject(childSchema, path + ".properties." + name, errors)
                    }
                }
            }
        }

        val requiredElement = schema["required"]
        if (requiredElement != null) {
            val required = requiredElement as? JsonArray
            if (required == null || required.any { (it as? JsonPrimitive)?.let { p -> !p.isString } != false }) {
                errors += path + ": required must be an array of strings"
            }
        }

        val additional = schema["additionalProperties"]
        if (additional != null) {
            when (additional) {
                is JsonObject -> validateSchemaObject(additional, path + ".additionalProperties", errors)
                is JsonPrimitive -> if (additional.booleanOrNull == null) {
                    errors += path + ": additionalProperties must be boolean or schema object"
                }
                else -> errors += path + ": additionalProperties must be boolean or schema object"
            }
        }

        val itemsElement = schema["items"]
        if (itemsElement != null) {
            val items = itemsElement as? JsonObject
            if (items == null) {
                errors += path + ": items must be a schema object"
            } else {
                validateSchemaObject(items, path + ".items", errors)
            }
        }

        val enumElement = schema["enum"]
        if (enumElement != null && ((enumElement as? JsonArray)?.isEmpty() != false)) {
            errors += path + ": enum must be a non-empty array"
        }

        val patternElement = schema["pattern"]
        if (patternElement != null) {
            val pattern = patternElement as? JsonPrimitive
            if (pattern == null || !pattern.isString) {
                errors += path + ": pattern must be a string"
            } else {
                runCatching { Regex(pattern.content) }
                    .onFailure { errors += path + ": pattern is not a valid regular expression" }
            }
        }

        val minLength = nonNegativeInteger(schema, "minLength", path, errors)
        val maxLength = nonNegativeInteger(schema, "maxLength", path, errors)
        if (minLength != null && maxLength != null && minLength > maxLength) {
            errors += path + ": minLength exceeds maxLength"
        }

        val minItems = nonNegativeInteger(schema, "minItems", path, errors)
        val maxItems = nonNegativeInteger(schema, "maxItems", path, errors)
        if (minItems != null && maxItems != null && minItems > maxItems) {
            errors += path + ": minItems exceeds maxItems"
        }

        val minimum = decimal(schema, "minimum", path, errors)
        val maximum = decimal(schema, "maximum", path, errors)
        if (minimum != null && maximum != null && minimum > maximum) {
            errors += path + ": minimum exceeds maximum"
        }

        val uniqueItems = schema["uniqueItems"]
        if (uniqueItems != null && ((uniqueItems as? JsonPrimitive)?.booleanOrNull == null)) {
            errors += path + ": uniqueItems must be boolean"
        }
    }

    private fun validateValue(
        value: JsonElement,
        schema: JsonObject,
        path: String,
        errors: MutableList<String>
    ) {
        val type = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != null && !matchesType(value, type)) {
            errors += path + ": expected " + type
            return
        }

        if (schema["const"] != null && schema["const"] != value) {
            errors += path + ": value does not match const"
        }
        val enumValues = schema["enum"] as? JsonArray
        if (enumValues != null && value !in enumValues) {
            errors += path + ": value is not in enum"
        }

        when (value) {
            is JsonObject -> validateObject(value, schema, path, errors)
            is JsonArray -> validateArray(value, schema, path, errors)
            JsonNull -> Unit
            is JsonPrimitive -> {
                if (value.isString) {
                    validateString(value.content, schema, path, errors)
                } else if (value.booleanOrNull == null) {
                    validateNumber(value, schema, path, errors)
                }
            }
        }
    }

    private fun validateObject(
        value: JsonObject,
        schema: JsonObject,
        path: String,
        errors: MutableList<String>
    ) {
        val required = (schema["required"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        required.filterNot { it in value }.forEach {
            errors += path + ": missing required property '" + it + "'"
        }

        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        properties.forEach { (name, childSchema) ->
            val childValue = value[name]
            val childObject = childSchema as? JsonObject
            if (childValue != null && childObject != null) {
                validateValue(childValue, childObject, path + "." + name, errors)
            }
        }

        val additional = schema["additionalProperties"]
        (value.keys - properties.keys).forEach { name ->
            when (additional) {
                is JsonPrimitive -> if (additional.booleanOrNull == false) {
                    errors += path + ": additional property '" + name + "' is not allowed"
                }
                is JsonObject -> value[name]?.let {
                    validateValue(it, additional, path + "." + name, errors)
                }
                else -> Unit
            }
        }
    }

    private fun validateArray(
        value: JsonArray,
        schema: JsonObject,
        path: String,
        errors: MutableList<String>
    ) {
        val minItems = (schema["minItems"] as? JsonPrimitive)?.content?.toIntOrNull()
        val maxItems = (schema["maxItems"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (minItems != null && value.size < minItems) errors += path + ": array has too few items"
        if (maxItems != null && value.size > maxItems) errors += path + ": array has too many items"
        if ((schema["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true &&
            value.distinct().size != value.size
        ) {
            errors += path + ": array items must be unique"
        }
        val itemSchema = schema["items"] as? JsonObject
        if (itemSchema != null) {
            value.forEachIndexed { index, item ->
                validateValue(item, itemSchema, path + "[" + index + "]", errors)
            }
        }
    }

    private fun validateString(
        value: String,
        schema: JsonObject,
        path: String,
        errors: MutableList<String>
    ) {
        val minLength = (schema["minLength"] as? JsonPrimitive)?.content?.toIntOrNull()
        val maxLength = (schema["maxLength"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (minLength != null && value.length < minLength) errors += path + ": string is too short"
        if (maxLength != null && value.length > maxLength) errors += path + ": string is too long"
        val pattern = (schema["pattern"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (pattern != null && !runCatching { Regex(pattern).containsMatchIn(value) }.getOrDefault(false)) {
            errors += path + ": string does not match pattern"
        }
    }

    private fun validateNumber(
        value: JsonPrimitive,
        schema: JsonObject,
        path: String,
        errors: MutableList<String>
    ) {
        val actual = value.content.toBigDecimalOrNull() ?: return
        val minimum = (schema["minimum"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()
        val maximum = (schema["maximum"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()
        if (minimum != null && actual < minimum) errors += path + ": number is below minimum"
        if (maximum != null && actual > maximum) errors += path + ": number exceeds maximum"
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> (value as? JsonPrimitive)?.isString == true
        "boolean" -> (value as? JsonPrimitive)?.booleanOrNull != null
        "integer" -> number(value)?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
        "number" -> number(value) != null
        "null" -> value == JsonNull
        else -> false
    }

    private fun number(value: JsonElement): java.math.BigDecimal? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString || primitive.booleanOrNull != null) return null
        return primitive.content.toBigDecimalOrNull()
    }

    private fun nonNegativeInteger(
        schema: JsonObject,
        key: String,
        path: String,
        errors: MutableList<String>
    ): Int? {
        val element = schema[key] ?: return null
        val primitive = element as? JsonPrimitive
        val value = primitive?.takeIf { !it.isString && it.booleanOrNull == null }?.content?.toIntOrNull()
        if (value == null || value < 0) {
            errors += path + ": " + key + " must be a non-negative integer"
            return null
        }
        return value
    }

    private fun decimal(
        schema: JsonObject,
        key: String,
        path: String,
        errors: MutableList<String>
    ): java.math.BigDecimal? {
        val element = schema[key] ?: return null
        val primitive = element as? JsonPrimitive
        val value = primitive?.takeIf { !it.isString && it.booleanOrNull == null }
            ?.content?.toBigDecimalOrNull()
        if (value == null) errors += path + ": " + key + " must be a number"
        return value
    }
}
