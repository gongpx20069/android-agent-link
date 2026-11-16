package com.gongpx.androidacpclient.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.pairing.TransferBundle
import com.gongpx.androidacpclient.data.pairing.TransferLink
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun incomingLinkNeverImportsUntilPasswordPreviewAndExplicitConfirmation() {
        val password = "separately shared password"
        val machine = Machine("m", "Computer", "wss://example.devtunnels.ms", "device-secret", "sha256:" + "a".repeat(64))
        val link = TransferLink.encrypt(TransferBundle(listOf(machine), emptyList()), password)
        var imports = 0
        var closed = false
        compose.setContent {
            MaterialTheme {
                TransferDialog(emptyList(), emptyList(), link, { _, headers -> headers }, {
                    imports++
                    assertEquals("device-secret", it.machines.single().deviceToken)
                }, { closed = true })
            }
        }
        assertEquals(0, imports)
        compose.onNodeWithText("Transfer password (12+ characters)").performTextInput(password)
        compose.onNodeWithText("Unlock preview").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Confirm import").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, imports)
        compose.onNodeWithText("Computer\nwss://example.devtunnels.ms").assertExists()
        compose.onNodeWithText("Confirm import").performClick()
        compose.waitUntil(3_000) { closed }
        assertEquals(1, imports)
    }
}
