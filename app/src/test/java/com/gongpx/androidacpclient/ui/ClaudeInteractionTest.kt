package com.gongpx.androidacpclient.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.gongpx.androidacpclient.data.model.Approval
import com.gongpx.androidacpclient.data.model.ApprovalAnswer
import com.gongpx.androidacpclient.data.model.ApprovalOption
import com.gongpx.androidacpclient.data.model.ApprovalStatus
import com.gongpx.androidacpclient.data.model.toBridgeApprovalRequest
import com.gongpx.androidacpclient.data.model.toApprovalDecisionResult
import com.gongpx.androidacpclient.data.store.ApprovalJsonCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ClaudeInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val approval = Approval(
        "id", "chat", "Chat", "machine", "Machine", "C:\\repo", "execute", "Run tests",
        createdAtMillis = 1, details = """{"command":"test"}""",
    )

    @Test fun permissionSelectsExactOptionInsteadOfFirstAllow() {
        var answer: ApprovalAnswer? = null
        val item = approval.copy(options = listOf(
            ApprovalOption("always", "Always allow", "allow_always"),
            ApprovalOption("once", "Allow once", "allow_once"),
        ))
        compose.setContent { MaterialTheme { ApprovalCard(item) { _, selected -> answer = selected } } }
        compose.onNodeWithText("Allow once").performClick()
        assertEquals("once", answer?.optionId)
        assertEquals(ApprovalStatus.Approved, answer?.status)
    }

    @Test fun questionHasAnswerFormNotApprovalButton() {
        var answer: ApprovalAnswer? = null
        val item = approval.copy(
            interaction = "question", summary = "Name your branch",
            requestedSchema = """{"type":"object","properties":{"branch":{"type":"string","title":"Branch"}},"required":["branch"]}""",
        )
        compose.setContent { MaterialTheme { ApprovalCard(item) { _, selected -> answer = selected } } }
        compose.onNodeWithText("Approve").assertDoesNotExist()
        compose.onNodeWithText("Send answer").performClick()
        assertNull(answer)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("feature/login")
        compose.onNodeWithText("Send answer").performClick()
        assertEquals("feature/login", JSONObject(requireNotNull(answer?.content)).getString("branch"))
        assertNull(answer?.optionId)
    }

    @Test fun questionOptionsAndSchemaSurviveEncryptedStoreCodec() {
        val item = approval.copy(interaction = "question", requestedSchema = """{"type":"object","properties":{"x":{"type":"string"}}}""",
            options = listOf(ApprovalOption("id", "Name", "allow_once")))
        assertEquals(item, ApprovalJsonCodec.decode(ApprovalJsonCodec.encode(listOf(item))).single())
        val request = JSONObject("""{"approvalId":"q","interaction":"question","requestedSchema":{"type":"object","properties":{"x":{"type":"string"}}},"options":[]}""")
            .toBridgeApprovalRequest()!!
        assertEquals("question", request.interaction)
        assertTrue(request.requestedSchema!!.contains("properties"))
    }

    @Test fun submittedOptionMustMatchAcknowledgedOption() {
        val response = JSONObject("""{"approvalId":"id","resolved":true,"status":"approved","optionId":"always"}""")
        assertThrows(IOException::class.java) { response.toApprovalDecisionResult("id", "approved", "once") }
    }

    @Test fun multiSelectAndNumbersRemainTypedWithoutInventingDefaults() {
        val schema = """{"type":"object","properties":{"n":{"type":"integer"},"flags":{"type":"array","items":{"enum":["a","b"]}},"note":{"type":"string"}}}"""
        val result = JSONObject(buildQuestionAnswers(schema, mapOf("n" to "2"), mapOf("flags" to setOf("a", "b")), emptyMap()))
        assertEquals(2, result.getInt("n"))
        assertEquals(2, result.getJSONArray("flags").length())
        assertFalse(result.has("note"))
        assertThrows(IllegalArgumentException::class.java) {
            buildQuestionAnswers(schema, mapOf("n" to "2.5"), emptyMap(), emptyMap())
        }
    }
}
