package dev.octoshrimpy.quik.feature.theme

import dev.octoshrimpy.quik.common.QkChangeHandler
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit & integration tests verifying:
 * 1. Material 3 Monet dynamic color gating and pure AMOLED black preservation using QkThemedActivity.
 * 2. QkChangeHandler width calculation, fallback to container width, and fallback to display width.
 * 3. Material 3 XML contracts for Toolbar styles, elevation, and contrast colors.
 */
class Material3ThemeTest {

    @Test
    fun testDynamicColorGating_disabledOnBlackTheme() {
        // Case 1: Dynamic color ON, AMOLED Black OFF -> Monet applied
        assertTrue(QkThemedActivity.shouldApplyDynamicColors(dynamicColor = true, black = false))

        // Case 2: Dynamic color ON, AMOLED Black ON -> Monet gated (disabled) to preserve true #000000 black
        assertFalse(QkThemedActivity.shouldApplyDynamicColors(dynamicColor = true, black = true))

        // Case 3: Dynamic color OFF, AMOLED Black OFF -> Monet disabled
        assertFalse(QkThemedActivity.shouldApplyDynamicColors(dynamicColor = false, black = false))

        // Case 4: Dynamic color OFF, AMOLED Black ON -> Monet disabled
        assertFalse(QkThemedActivity.shouldApplyDynamicColors(dynamicColor = false, black = true))
    }

    @Test
    fun testQkChangeHandler_effectiveWidthCalculations() {
        val displayWidth = 1080
        val containerWidth = 720
        val measuredViewWidth = 600

        // 1. Measured view takes precedence
        assertEquals(600f, QkChangeHandler.calculateEffectiveWidth(measuredViewWidth, containerWidth, displayWidth), 0.001f)

        // 2. Unmeasured view (0) falls back to container width
        assertEquals(720f, QkChangeHandler.calculateEffectiveWidth(0, containerWidth, displayWidth), 0.001f)

        // 3. Unmeasured view AND unmeasured container (e.g. cold start onCreate) falls back to display width
        assertEquals(1080f, QkChangeHandler.calculateEffectiveWidth(0, 0, displayWidth), 0.001f)
    }

    @Test
    fun testQkChangeHandler_pushTransitionMath() {
        val displayWidth = 1080
        val containerWidth = 1080

        // When to.width == 0, effective width falls back to containerWidth
        val toEffectiveWidth = QkChangeHandler.calculateEffectiveWidth(0, containerWidth, displayWidth)
        assertEquals(1080f, toEffectiveWidth, 0.001f)

        // Push 'to' view starts at +width and animates to 0
        val pushToStartX = toEffectiveWidth
        assertEquals(1080f, pushToStartX, 0.001f)

        // Push 'from' view animates to -width / 4
        val fromEffectiveWidth = QkChangeHandler.calculateEffectiveWidth(0, containerWidth, displayWidth)
        val pushFromTargetX = -fromEffectiveWidth / 4
        assertEquals(-270f, pushFromTargetX, 0.001f)
    }

    @Test
    fun testQkChangeHandler_popTransitionMath() {
        val displayWidth = 1080
        val containerWidth = 1080

        // Pop 'from' view slides out to +width
        val fromEffectiveWidth = QkChangeHandler.calculateEffectiveWidth(0, containerWidth, displayWidth)
        assertEquals(1080f, fromEffectiveWidth, 0.001f)

        // Pop 'to' view slides in from (fromLeft - toWidth / 4) to 0
        val toEffectiveWidth = QkChangeHandler.calculateEffectiveWidth(0, containerWidth, displayWidth)
        val fromLeft = 0f
        val popToStartX = fromLeft - toEffectiveWidth / 4
        assertEquals(-270f, popToStartX, 0.001f)
    }

    @Test
    fun testMaterial3StylesXmlContract() {
        val stylesFile = File("src/main/res/values/styles.xml")
        val content = if (stylesFile.exists()) stylesFile.readText() else File("presentation/src/main/res/values/styles.xml").readText()

        // Verify Toolbar style uses colorSurface and 0dp elevation
        assertTrue("Toolbar style must use colorSurface background",
            content.contains("<item name=\"android:background\">?attr/colorSurface</item>"))
        assertTrue("Toolbar style must specify 0dp elevation",
            content.contains("<item name=\"android:elevation\">0dp</item>"))
        assertTrue("Toolbar style must tint navigation icon with colorOnSurface",
            content.contains("<item name=\"navigationIconTint\">?attr/colorOnSurface</item>"))

        // Verify ToolbarText style uses colorOnSurface for WCAG contrast
        assertTrue("ToolbarText style must use colorOnSurface",
            content.contains("<item name=\"android:textColor\">?attr/colorOnSurface</item>"))
    }

    @Test
    fun testMaterial3ContainerActivityXmlContract() {
        val layoutFile = File("src/main/res/layout/container_activity.xml")
        val content = if (layoutFile.exists()) layoutFile.readText() else File("presentation/src/main/res/layout/container_activity.xml").readText()

        assertTrue("container_activity toolbar must specify 0dp elevation",
            content.contains("android:elevation=\"0dp\""))
        assertTrue("container_activity toolbar must specify colorOnSurface navigation icon tint",
            content.contains("app:navigationIconTint=\"?attr/colorOnSurface\""))
    }
}
