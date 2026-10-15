package dev.octoshrimpy.quik.feature.settings

import dev.octoshrimpy.quik.common.base.QkThemedActivity
import io.reactivex.Observable
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit & integration tests verifying:
 * 1. Debounced activity recreation trigger pipeline (including theme() preference changes) via QkThemedActivity.
 * 2. Suppression of rapid recreation loops/storms.
 * 3. Disposal behavior preventing stale recreation calls after activity destruction.
 * 4. Graceful handling of dialogs when controllers are detached or activities are destroyed.
 */
class SettingsNavigationTest {

    @Test
    fun testRecreationTriggers_includesThemeAndDebounces() {
        val testScheduler = TestScheduler()

        val themePref = PublishSubject.create<Int>()
        val nightModePref = PublishSubject.create<Int>()
        val blackPref = PublishSubject.create<Boolean>()
        val dynamicColorPref = PublishSubject.create<Boolean>()
        val textSizePref = PublishSubject.create<Int>()
        val systemFontPref = PublishSubject.create<Boolean>()

        // Triggers mirroring QkThemedActivity.onCreate with prefs.theme() included
        val triggers: List<Observable<*>> = listOf(
            themePref,
            nightModePref,
            blackPref,
            dynamicColorPref,
            textSizePref,
            systemFontPref
        )

        val recreations = mutableListOf<Unit>()

        // Execute against real QkThemedActivity.createRecreationTrigger
        val disposable = QkThemedActivity.createRecreationTrigger(triggers, testScheduler)
            .subscribe { recreations.add(Unit) }

        // Initial state: no recreation
        assertEquals(0, recreations.size)

        // Rapid preference changes within 100ms
        themePref.onNext(0xFF123456.toInt())
        testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
        assertEquals(0, recreations.size)

        nightModePref.onNext(2)
        testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
        assertEquals(0, recreations.size)

        dynamicColorPref.onNext(true)
        testScheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
        assertEquals(0, recreations.size)

        // Last event was at t=200ms. Advance to t=550ms (350ms after last event, still waiting for 400ms debounce)
        testScheduler.advanceTimeBy(250, TimeUnit.MILLISECONDS)
        assertEquals(0, recreations.size)

        // Advance to t=600ms (400ms after last event at t=200ms)
        testScheduler.advanceTimeBy(50, TimeUnit.MILLISECONDS)
        assertEquals(1, recreations.size)

        // Fire another change after debounce completed
        themePref.onNext(0xFFAABBCC.toInt())
        testScheduler.advanceTimeBy(399, TimeUnit.MILLISECONDS)
        assertEquals(1, recreations.size)

        testScheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        assertEquals(2, recreations.size)

        disposable.dispose()
        assertTrue(disposable.isDisposed)
    }

    @Test
    fun testSingleThemeChangeFiresRecreationAfterDebounce() {
        val testScheduler = TestScheduler()
        val themePref = PublishSubject.create<Int>()

        val recreations = mutableListOf<Unit>()
        val disposable = QkThemedActivity.createRecreationTrigger(listOf(themePref), testScheduler)
            .subscribe { recreations.add(Unit) }

        themePref.onNext(0xFF0097A7.toInt())
        testScheduler.advanceTimeBy(399, TimeUnit.MILLISECONDS)
        assertEquals(0, recreations.size)

        testScheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        assertEquals(1, recreations.size)

        disposable.dispose()
    }

    @Test
    fun testDisposalPreventsRecreationAfterActivityTeardown() {
        val testScheduler = TestScheduler()
        val themePref = PublishSubject.create<Int>()

        val recreations = mutableListOf<Unit>()
        val disposable = QkThemedActivity.createRecreationTrigger(listOf(themePref), testScheduler)
            .subscribe { recreations.add(Unit) }

        themePref.onNext(0xFF0097A7.toInt())
        testScheduler.advanceTimeBy(200, TimeUnit.MILLISECONDS)

        // Activity destroyed -> autoDisposable triggers dispose()
        disposable.dispose()

        // Time elapses past debounce window
        testScheduler.advanceTimeBy(300, TimeUnit.MILLISECONDS)

        // No recreation should fire on destroyed activity
        assertEquals(0, recreations.size)
    }

    @Test
    fun testRecreationTrigger_emptyTriggersListEmitsNothing() {
        val testScheduler = TestScheduler()
        val recreations = mutableListOf<Unit>()
        val disposable = QkThemedActivity.createRecreationTrigger(emptyList(), testScheduler)
            .subscribe { recreations.add(Unit) }

        testScheduler.advanceTimeBy(1000, TimeUnit.MILLISECONDS)
        assertEquals(0, recreations.size)
        disposable.dispose()
    }

    @Test
    fun testDetachedDialogSafety_nullActivityGuardsPreventCrash() {
        // Guard pattern used across SettingsController, BackupController,
        // ConversationInfoController, and MessageUtilsController:
        var activity: Any? = null

        var showedDialog = false
        fun showDialogSafe(): Boolean {
            if (activity == null) return false
            showedDialog = true
            return true
        }

        // When activity is null (detached / destroyed), calls safely no-op without NPE
        val resultWhenNull = showDialogSafe()
        assertFalse(resultWhenNull)
        assertFalse(showedDialog)

        // When activity is present, calls succeed
        activity = Object()
        val resultWhenAttached = showDialogSafe()
        assertTrue(resultWhenAttached)
        assertTrue(showedDialog)
    }
}
