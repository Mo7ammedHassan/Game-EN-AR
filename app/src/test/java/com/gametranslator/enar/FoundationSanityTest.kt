package com.gametranslator.enar

import com.gametranslator.enar.model.BoundingBox
import com.gametranslator.enar.model.EngineMetadata
import com.gametranslator.enar.model.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Phase 1 Foundation Sanity Unit Test.
 */
class FoundationSanityTest {

    @Test
    fun testTextRegionDataModelCreation() {
        val bounds = BoundingBox(10, 20, 100, 50)
        val region = TextRegion(
            id = "region_001",
            bounds = bounds,
            confidence = 0.95f,
            lineId = 1
        )

        assertEquals("region_001", region.id)
        assertEquals(10, region.bounds.left)
        assertEquals(20, region.bounds.top)
        assertEquals(100, region.bounds.right)
        assertEquals(50, region.bounds.bottom)
        assertEquals(0.95f, region.confidence, 0.001f)
    }

    @Test
    fun testEngineMetadataModel() {
        val metadata = EngineMetadata(
            engineName = "PP-OCRv4 Mobile",
            modelVersion = "v4.0.0",
            executionBackend = "CPU_XNNPACK",
            isQuantized = true
        )

        assertNotNull(metadata)
        assertEquals("PP-OCRv4 Mobile", metadata.engineName)
        assertEquals("CPU_XNNPACK", metadata.executionBackend)
    }
}
