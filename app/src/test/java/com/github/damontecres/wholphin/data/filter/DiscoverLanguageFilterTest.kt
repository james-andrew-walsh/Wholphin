package com.github.damontecres.wholphin.data.filter

import com.github.damontecres.wholphin.api.seerr.model.LanguagesGet200ResponseInner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverLanguageFilterTest {
    private fun language(
        code: String?,
        name: String?,
    ) = LanguagesGet200ResponseInner(iso6391 = code, englishName = name, name = name)

    @Test
    fun `English comes first and the rest sort by name`() {
        val options =
            discoverLanguageOptions(
                listOf(
                    language("fr", "French"),
                    language("xx", "No Language"),
                    language("en", "English"),
                    language("de", "German"),
                    language(null, "Broken"),
                    language("ja", ""),
                    language("ar", "Arabic"),
                ),
            )

        assertEquals(
            listOf(
                FilterValueOption("English", "en"),
                FilterValueOption("Arabic", "ar"),
                FilterValueOption("French", "fr"),
                FilterValueOption("German", "de"),
            ),
            options,
        )
    }

    @Test
    fun `the filter sets and clears the original language`() {
        val filter = DiscoverLanguageFilter.set("en", DiscoverFilter(genreIds = listOf(18)))

        assertEquals("en", filter.language)
        assertEquals("en", DiscoverLanguageFilter.get(filter))
        assertEquals(1, filter.countFilters(listOf(DiscoverLanguageFilter)))
        assertNull(DiscoverLanguageFilter.get(filter.delete(listOf(DiscoverLanguageFilter))))
        assertFalse(DiscoverLanguageFilter.supportMultiple)
    }

    @Test
    fun `only the browse grids get the language filter`() {
        assertTrue(DiscoverLanguageFilter in browseMovieFilters)
        assertTrue(DiscoverLanguageFilter in browseTvFilters)
        assertFalse(DiscoverLanguageFilter in discoverMovieFilters)
        assertFalse(DiscoverLanguageFilter in discoverTvFilters)
    }
}
