package com.github.damontecres.wholphin.ui.components

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.data.model.MovieRatings
import com.github.damontecres.wholphin.test.TestActivity
import com.github.damontecres.wholphin.ui.performClickEnter
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration

private const val IMDB_URL = "https://www.imdb.com/title/tt0078748"
private const val RT_URL = "https://www.rottentomatoes.com/m/alien"
private const val OVERVIEW = "In deep space, the crew of a commercial spaceship finds something it should have left alone"

@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class RatingChipsTest {
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

    private val clicked = mutableListOf<String>()
    private var focusCount = 0
    private val buttons = FocusRequester()

    private val ratings =
        MovieRatings(
            imdbScore = 8.4f,
            imdbUrl = IMDB_URL,
            rottenTomatoesCritics = 93,
            rottenTomatoesUrl = RT_URL,
        )

    /**
     * The chips with what is around them on the movie pages, starting with focus on the buttons
     */
    private fun setContent(
        ratings: MovieRatings = this.ratings,
        showImdb: Boolean = true,
        showRottenTomatoes: Boolean = true,
    ) {
        composeTestRule.setContent {
            WholphinTheme {
                Column {
                    OverviewText(
                        overview = OVERVIEW,
                        maxLines = 3,
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(.6f),
                    )
                    RatingChips(
                        ratings = ratings,
                        imdbFocusRequester = remember { FocusRequester() },
                        rottenTomatoesFocusRequester = remember { FocusRequester() },
                        onClickImdb = { clicked.add(it) },
                        onClickRottenTomatoes = { clicked.add(it) },
                        onFocused = { focusCount++ },
                        showImdb = showImdb,
                        showRottenTomatoes = showRottenTomatoes,
                    )
                    ExpandablePlayButtons(
                        title = "Alien",
                        resumePosition = Duration.ZERO,
                        watched = false,
                        favorite = false,
                        canDelete = false,
                        trailers = null,
                        playOnClick = {},
                        watchOnClick = {},
                        favoriteOnClick = {},
                        moreOnClick = {},
                        trailerOnClick = {},
                        onConfirmDelete = {},
                        chooseVersionParams = null,
                        buttonOnFocusChanged = {},
                        modifier = Modifier.fillMaxWidth().focusRequester(buttons),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { buttons.requestFocus() }
        composeTestRule.waitForIdle()
    }

    private fun node(text: String) = composeTestRule.onNodeWithText(text)

    @OptIn(ExperimentalTestApi::class)
    private fun SemanticsNodeInteraction.press(key: Key) {
        performKeyInput { pressKey(key) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `Up from the buttons reaches the chips and Down returns`() {
        setContent()
        node("Play").assertIsFocused().press(Key.DirectionUp)
        node("IMDb 8.4").assertIsFocused().press(Key.DirectionDown)
        node("Play").assertIsFocused()
    }

    @Test
    fun `Up from the chips reaches the overview and Down returns`() {
        setContent()
        node("Play").press(Key.DirectionUp)
        node("IMDb 8.4").press(Key.DirectionUp)
        node(OVERVIEW).assertIsFocused().press(Key.DirectionDown)
        // Which of them depends on how wide the overview is
        composeTestRule.onNode(isFocused() and (hasText("IMDb 8.4") or hasText("93%"))).assertExists()
    }

    @Test
    fun `Left and right move between the chips`() {
        setContent()
        node("Play").press(Key.DirectionUp)
        node("IMDb 8.4").press(Key.DirectionRight)
        node("93%").assertIsFocused().press(Key.DirectionLeft)
        node("IMDb 8.4").assertIsFocused()
    }

    @Test
    fun `Focusing a chip is reported so that the page can bring it into view`() {
        setContent()
        assertEquals(0, focusCount)
        node("Play").press(Key.DirectionUp)
        assertEquals(1, focusCount)
    }

    @Test
    fun `Selecting a chip gives its page`() {
        setContent()
        node("Play").press(Key.DirectionUp)
        node("IMDb 8.4").performClickEnter()
        node("IMDb 8.4").press(Key.DirectionRight)
        node("93%").performClickEnter()
        composeTestRule.waitForIdle()
        assertEquals(listOf(IMDB_URL, RT_URL), clicked)
    }

    @Test
    fun `A chip without a score only has the label`() {
        setContent(MovieRatings(imdbUrl = IMDB_URL, rottenTomatoesUrl = RT_URL))
        node("IMDb").assertExists()
        node("Rotten Tomatoes").assertExists()
    }

    @Test
    fun `A score without a URL has no chip`() {
        setContent(MovieRatings(imdbScore = 8.4f, rottenTomatoesCritics = 93, rottenTomatoesUrl = RT_URL))
        node("IMDb 8.4").assertDoesNotExist()
        node("93%").assertExists()
    }

    @Test
    fun `A hidden rating has no chip`() {
        setContent(showRottenTomatoes = false)
        node("IMDb 8.4").assertExists()
        node("93%").assertDoesNotExist()
    }

    @Test
    fun `Nothing to show leaves focus on the buttons`() {
        setContent(showImdb = false, showRottenTomatoes = false)
        node("Play").assertIsFocused().press(Key.DirectionUp)
        node(OVERVIEW).assertIsFocused()
    }
}
