package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F15 (userflow-ef96bbf0 a15s-xiaomi, about 22:47): the Xiaomi still showed the "not found nearby" dialog of its own earlier
 * contact call when a call rang; the ringing screen sat under it and Accept could not be reached. Every dialog of the
 * activity is tracked and closed when a call rings or the call screen opens; the not-found dialog closes itself.
 */
class CallScreenDialogsTest {
    private val main = listOf(
        File("src/main/java/com/example/bluetoothtest/MainActivity.kt"),
        File("app/src/main/java/com/example/bluetoothtest/MainActivity.kt"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun everyDialogOfTheActivityIsTracked() {
        val builders = Regex("""(MaterialAlertDialogBuilder|AlertDialog\.Builder)\(""").findAll(main).count()
        val tracked = Regex("""\.showTracked\(\)""").findAll(main).count() - 1 // minus the helper's own definition
        assertEquals(builders, tracked)
        assertTrue("the scan found the activity's dialogs", builders > 0)
    }

    @Test
    fun aRingingCallAndTheCallScreenCloseEveryDialogFirst() {
        val incoming = main.substringAfter("onIncoming = { peerLabel ->").substringBefore("val interruptedScreen")
        assertTrue(incoming.contains("dismissOpenDialogs(\"incoming_call\")"))
        val callScreen = main.substringAfter("private fun showPrivateCallScreen(message: String) {").substringBefore("stopPrivateCallDiscovery()")
        assertTrue(callScreen.contains("dismissOpenDialogs(\"call_screen\")"))
        val dismiss = main.substringAfter("private fun dismissOpenDialogs(").substringBefore("\n    }\n")
        assertTrue("the contact sheet too", dismiss.contains("contactActions.dismissOpen()"))
    }

    /**
     * userflow-ef96bbf0 xiaomi-asus (23:37-23:38): the one-time full-screen offer, a modal, hid the Recent list and then
     * the Accept button of a ringing call. It is a non-blocking banner now, never shown during a call, closed by a call.
     */
    @Test
    fun theFullScreenOfferIsABannerNeverAModalAndNeverDuringACall() {
        val offer = main.substringAfter("private fun checkFullScreenCallAccess(").substringBefore("private var fullScreenAccessBanner")
        assertTrue(offer.contains("Snackbar"))
        assertTrue("no modal", !offer.contains("MaterialAlertDialogBuilder"))
        assertTrue(offer.contains("|| privateCallPhase != null) return"))
        val dismiss = main.substringAfter("private fun dismissOpenDialogs(").substringBefore("\n    }\n")
        assertTrue(dismiss.contains("fullScreenAccessBanner?.dismiss()"))
    }

    @Test
    fun theNotFoundDialogClosesItselfAfterAMinute() {
        val notFound = main.substringAfter("private fun showContactNotFound(").substringBefore("private val openDialogs")
        assertTrue(notFound.contains("val shown = dialog.showTracked()"))
        assertTrue(notFound.contains("NOT_FOUND_DIALOG_MS"))
    }
}
