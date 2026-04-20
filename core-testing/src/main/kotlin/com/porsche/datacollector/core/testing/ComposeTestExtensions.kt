package com.porsche.datacollector.core.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText

/**
 * Find a node by test tag and assert it is displayed.
 */
fun ComposeContentTestRule.assertTagDisplayed(
    tag: String,
): SemanticsNodeInteraction = onNodeWithTag(tag).assertIsDisplayed()

/**
 * Find a node by text content and assert it is displayed.
 */
fun ComposeContentTestRule.assertTextDisplayed(
    text: String,
): SemanticsNodeInteraction = onNodeWithText(text).assertIsDisplayed()

/**
 * Find a node by content description and assert it is displayed.
 */
fun ComposeContentTestRule.assertContentDescriptionDisplayed(
    description: String,
): SemanticsNodeInteraction = onNodeWithContentDescription(description).assertIsDisplayed()

/**
 * Find a node by test tag and assert its text content.
 */
fun ComposeContentTestRule.assertTagHasText(
    tag: String,
    expected: String,
): SemanticsNodeInteraction = onNodeWithTag(tag).assertTextEquals(expected)
