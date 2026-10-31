package com.github.damontecres.wholphin.services

import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.AppUpgradeHandler.Companion.upgradeStartVersion
import com.github.damontecres.wholphin.util.Version
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

class AppUpgradeHandlerTest {
    private class FakeDataStore(
        initial: AppPreferences,
    ) : DataStore<AppPreferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<AppPreferences> = state

        override suspend fun updateData(transform: suspend (t: AppPreferences) -> AppPreferences): AppPreferences =
            transform(state.value).also { state.value = it }

        val value: AppPreferences get() = state.value
    }

    @Test
    fun `Upstream flavors upgrade from the stored version`() {
        Assert.assertEquals(
            Version.fromString("1.0.8-3-g0"),
            upgradeStartVersion("1.0.8-3-g0", null, ""),
        )
    }

    @Test
    fun `Fresh installs run every upgrade`() {
        Assert.assertEquals(Version.fromString("0.0.0"), upgradeStartVersion(null, null, ""))
        Assert.assertEquals(Version.fromString("0.0.0"), upgradeStartVersion(null, null, "1.0.9"))
    }

    @Test
    fun `Own version numbers are not compared to upstream thresholds`() {
        val lastThreshold = Version.fromString("1.0.6-7-g0")
        Assert.assertTrue(Version.fromString("0.1.0").isEqualOrBefore(lastThreshold))
        Assert.assertFalse(upgradeStartVersion("0.1.0", "1.0.9", "1.0.9").isEqualOrBefore(lastThreshold))
        // A build from before the baseline was stored
        Assert.assertFalse(upgradeStartVersion("0.1.0", null, "1.0.9").isEqualOrBefore(lastThreshold))
    }

    @Test
    fun `New upstream thresholds run once after a merge`() {
        val newThreshold = Version.fromString("1.0.9-3-g0")
        Assert.assertTrue(upgradeStartVersion("0.1.1", "1.0.9", "1.0.10").isEqualOrBefore(newThreshold))
        Assert.assertFalse(upgradeStartVersion("0.1.2", "1.0.10", "1.0.10").isEqualOrBefore(newThreshold))
    }

    @Test
    fun `Upgrading from an own version keeps settings`() =
        runTest {
            val prefs =
                FakeDataStore(AppPreferences.newBuilder().setSignInAutomatically(false).build())
            val handler = AppUpgradeHandler(mockk(), prefs, mockk(), mockk(), mockk())
            handler.upgradeApp(
                upgradeStartVersion("0.1.0", "1.0.9", "1.0.9"),
                Version.fromString("0.1.1"),
                prefs,
            )
            Assert.assertFalse(prefs.value.signInAutomatically)
        }
}
