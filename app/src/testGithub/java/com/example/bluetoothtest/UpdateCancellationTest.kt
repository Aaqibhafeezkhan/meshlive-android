package com.example.bluetoothtest

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class UpdateCancellationTest {
    @Test fun aCancelTapWhileTheVerifiedResultIsQueuedDoesNotPublishReady() {
        val file = Files.createTempFile("update-cancel", ".apk").toFile()
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val ready = PrepareResult.Ready(PreparedUpdate(file, "hash", "0.4.0", 42, 3, "github"))
            assertSame(ready, UpdateScreenModel.finishPreparation(ready, false))
            assertTrue(file.exists())
            val cancelled = UpdateScreenModel.finishPreparation(ready, true)
            assertEquals(PrepareResult.Kind.CANCELLED, (cancelled as PrepareResult.Failed).kind)
            assertFalse(file.exists())
        } finally { file.delete() }
    }
}
