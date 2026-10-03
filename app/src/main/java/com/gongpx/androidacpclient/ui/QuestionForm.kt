package com.gongpx.androidacpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun QuestionForm(
    schema: String,
    enabled: Boolean,
    submitLabel: String,
    invalidLabel: String,
    onSubmit: (String) -> Unit,
) {
    val parsed = remember(schema) {
        runCatching {
            JSONObject(schema).getJSONObject("properties").also { properties ->
                require(properties.length() in 1..24)
                properties.keys().forEach { key ->
                    val field = properties.getJSONObject(key)
                    require(field.getString("type") in setOf("string", "integer", "number", "array", "boolean"))
                    questionOptions(if (field.getString("type") == "array") field.getJSONObject("items") else field)
                }
            }
        }
    }
    val properties = parsed.getOrNull()
    if (properties == null) {
        Text(invalidLabel, color = MaterialTheme.colorScheme.error)
        return
    }
    val text = remember(schema) { mutableStateMapOf<String, String>() }
    val choices = remember(schema) { mutableStateMapOf<String, Set<String>>() }
    val booleans = remember(schema) { mutableStateMapOf<String, Boolean>() }
    var error by remember(schema) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        properties.keys().forEach { key ->
            val field = properties.getJSONObject(key)
            val type = field.getString("type")
            Text(field.optString("title").ifBlank { key }, style = MaterialTheme.typography.titleSmall)
            field.optString("description").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            val options = questionOptions(if (type == "array") field.getJSONObject("items") else field)
            if (options.isNotEmpty()) {
                options.forEach { (value, label, description) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (type == "array") {
                            Checkbox(
                                checked = value in choices[key].orEmpty(), enabled = enabled,
                                onCheckedChange = { checked ->
                                    choices[key] = if (checked) choices[key].orEmpty() + value else choices[key].orEmpty() - value
                                },
                            )
                        } else {
                            RadioButton(selected = text[key] == value, enabled = enabled, onClick = { text[key] = value })
                        }
                        Column {
                            Text(label)
                            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (type == "boolean") {
                Checkbox(checked = booleans[key] == true, enabled = enabled, onCheckedChange = { booleans[key] = it })
            } else {
                OutlinedTextField(
                    value = text[key].orEmpty(), onValueChange = { text[key] = it },
                    enabled = enabled, modifier = Modifier.fillMaxWidth(), maxLines = 4,
                )
            }
        }
    }
    if (error) Text(invalidLabel, color = MaterialTheme.colorScheme.error)
    Button(enabled = enabled, onClick = {
        try {
            val answers = buildQuestionAnswers(schema, text, choices, booleans)
            error = false
            onSubmit(answers)
        } catch (_: IllegalArgumentException) {
            error = true
        }
    }) { Text(submitLabel) }
}

private fun questionOptions(field: JSONObject): List<Triple<String, String, String>> {
    val variants = field.optJSONArray("oneOf") ?: field.optJSONArray("anyOf")
    if (variants != null) return List(variants.length()) { index ->
        val option = variants.getJSONObject(index)
        Triple(option.getString("const"), option.optString("title", option.getString("const")), option.optString("description"))
    }
    val values = field.optJSONArray("enum") ?: return emptyList()
    val names = field.optJSONArray("enumNames")
    return List(values.length()) { index ->
        val value = values.getString(index)
        Triple(value, names?.optString(index)?.takeIf { it.isNotBlank() } ?: value, "")
    }
}

internal fun buildQuestionAnswers(
    schema: String, text: Map<String, String>, choices: Map<String, Set<String>>, booleans: Map<String, Boolean>,
): String {
    val form = JSONObject(schema)
    val properties = form.getJSONObject("properties")
    val answers = JSONObject()
    properties.keys().forEach { key ->
        val field = properties.getJSONObject(key)
        when (field.getString("type")) {
            "array" -> choices[key]?.let { answers.put(key, JSONArray(it.toList())) }
            "boolean" -> answers.put(key, booleans[key] ?: false)
            "integer" -> text[key]?.takeIf { it.isNotBlank() }?.let {
                answers.put(key, requireNotNull(it.toLongOrNull()))
            }
            "number" -> text[key]?.takeIf { it.isNotBlank() }?.let {
                val number = requireNotNull(it.toDoubleOrNull())
                require(number.isFinite())
                answers.put(key, number)
            }
            "string" -> text[key]?.let { answers.put(key, it) }
        }
    }
    form.optJSONArray("required")?.let { required ->
        repeat(required.length()) { require(answers.has(required.getString(it))) }
    }
    return answers.toString()
}
