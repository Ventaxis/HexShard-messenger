package com.example

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AppLanguage
import com.example.ui.LocalizationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Spec: a user entering from Russia gets Russian, a user from any other country gets
 * English, and "follow the system" must be an explicit, always-available choice that
 * re-detects instead of sticking to a previously picked language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalizationRegionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        Locale.setDefault(Locale("en", "US"))
        context.getSharedPreferences("hexshard_profile", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun setSimCountry(country: String?) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        Shadows.shadowOf(tm).setSimCountryIso(country)
        Shadows.shadowOf(tm).setNetworkCountryIso(country)
    }

    @Test
    fun `sim country RU resolves Russian`() {
        setSimCountry("ru")
        assertEquals(AppLanguage.RUSSIAN, LocalizationManager.detectByRegion(context))
    }

    @Test
    fun `sim country outside Russia resolves English even with ru device language`() {
        setSimCountry("de")
        Locale.setDefault(Locale("ru", "RU"))
        assertEquals(AppLanguage.ENGLISH, LocalizationManager.detectByRegion(context))
    }

    @Test
    fun `no telephony info falls back to device locale language`() {
        setSimCountry(null)
        Locale.setDefault(Locale("ru", "RU"))
        assertEquals(AppLanguage.RUSSIAN, LocalizationManager.detectByRegion(context))
        Locale.setDefault(Locale("en", "US"))
        assertEquals(AppLanguage.ENGLISH, LocalizationManager.detectByRegion(context))
    }

    @Test
    fun `system selection stores marker and re-detects on next init`() {
        setSimCountry("ru")
        LocalizationManager.setLanguage(context, AppLanguage.ENGLISH) // explicit pick
        assertEquals(AppLanguage.ENGLISH, LocalizationManager.currentLanguage.value)
        assertFalse(LocalizationManager.isSystemSelected)

        LocalizationManager.setSystemLanguage(context)
        assertTrue(LocalizationManager.isSystemSelected)
        assertEquals(AppLanguage.RUSSIAN, LocalizationManager.currentLanguage.value)

        // Marker survives re-init (app restart): RU sim -> Russian again, not the picked English
        LocalizationManager.init(context)
        assertEquals(AppLanguage.RUSSIAN, LocalizationManager.currentLanguage.value)
        assertTrue(LocalizationManager.isSystemSelected)
    }
}
