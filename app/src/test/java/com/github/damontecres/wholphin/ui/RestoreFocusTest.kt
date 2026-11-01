package com.github.damontecres.wholphin.ui

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.test.TestActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class RestoreFocusTest {
    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val addActivityToRobolectricRule =
        object : TestWatcher() {
            override fun starting(description: Description?) {
                super.starting(description)
                val appContext: Application = ApplicationProvider.getApplicationContext()
                val activityInfo =
                    ActivityInfo().apply {
                        name = TestActivity::class.java.name
                        packageName = appContext.packageName
                    }
                shadowOf(appContext.packageManager).addOrUpdateActivity(activityInfo)
            }
        }

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    private fun setContent(wantedAttached: Boolean) {
        composeTestRule.setContent {
            val wanted = remember { FocusRequester() }
            val fallback = remember { FocusRequester() }
            RequestOrRestoreFocus(wanted, fallback)
            Column {
                Box(
                    Modifier
                        .size(10.dp)
                        .testTag("fallback")
                        .focusRequester(fallback)
                        .focusable(),
                )
                if (wantedAttached) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .testTag("wanted")
                            .focusRequester(wanted)
                            .focusable(),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `The wanted requester gets focus when attached`() {
        setContent(wantedAttached = true)
        composeTestRule.onNodeWithTag("wanted").assertIsFocused()
        composeTestRule.onNodeWithTag("fallback").assertIsNotFocused()
    }

    @Test
    fun `The fallback gets focus when the wanted requester is not attached`() {
        setContent(wantedAttached = false)
        composeTestRule.onNodeWithTag("fallback").assertIsFocused()
    }
}
