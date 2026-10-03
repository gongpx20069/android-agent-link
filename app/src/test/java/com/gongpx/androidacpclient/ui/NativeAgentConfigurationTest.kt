package com.gongpx.androidacpclient.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.gongpx.androidacpclient.data.model.ConfigOption
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NativeAgentConfigurationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun groupedModelValuesPreserveProviderIds() {
        val options = configOptionValues(JSONArray("""[
            {"group":"provider","name":"Provider","options":[
                {"value":"openai/model","name":"Model","description":"Remote model"}]},
            {"value":"local/model","name":"Model"}
        ]"""))
        assertEquals(listOf("openai/model", "local/model"), options.map { it.value })
        assertEquals("Remote model", options[0].description)
    }

    @Test fun genericSettingsExposeModeAndThinkingWithoutChangingThem() {
        val options = listOf("mode", "thinking").map { id ->
            ConfigOption(id = id, name = id, category = id, type = "select", currentValue = "default", options = emptyList())
        }
        var selected: ConfigOption? = null
        compose.setContent { MaterialTheme { ConfigurationOptionsDialog(options, {}, { selected = it }) } }
        assertNull(selected)
        compose.onNodeWithText("thinking: default").performClick()
        assertEquals("thinking", selected?.id)
    }

    @Test fun qwenQuestionFormRequiresAnswersAndPreservesFreeText() {
        val schema = """{"type":"object","properties":{"0":{"type":"string","title":"Features?","minLength":1,"description":"A: First\nB: Second\nEnter labels or a custom answer."}},"required":["0"]}"""
        var answer: String? = null
        compose.setContent { MaterialTheme { QuestionForm(schema, true, "Submit", "Answer required") { answer = it } } }
        compose.onNodeWithText("Submit").performClick()
        assertNull(answer)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("A, B, custom")
        compose.onNodeWithText("Submit").performClick()
        assertEquals("A, B, custom", JSONObject(requireNotNull(answer)).getString("0"))
    }
}
