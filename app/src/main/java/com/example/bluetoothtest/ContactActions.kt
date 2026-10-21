package com.example.bluetoothtest

import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService

/**
 * A saved contact's detail sheet (the Contacts tab): voice and video call, rename, block or unblock, and
 * remove, with the full ID and when/how the person was verified. Store access runs on [worker]; [onChanged]
 * runs on the main thread after a rename or removal so the list reloads; [onCall] starts the call back.
 */
internal class ContactActions(
    private val activity: AppCompatActivity,
    private val worker: ExecutorService,
    private val onChanged: () -> Unit,
    private val onCall: (ContactEntry, video: Boolean) -> Unit,
    /** Whether this phone can recognise the contact nearby ([ContactReach]); called on [worker]. */
    private val hintFor: (ContactEntry) -> ContactReach.Hint = { ContactReach.Hint.RECOGNISABLE },
    /** "Message" in the contact sheet (only with the chat UI on); null hides the row. */
    private val onMessage: (() -> ((ContactEntry) -> Unit)?) = { null },
) {
    private val security: SecurityUi get() = SecurityUiBackend.current

    /** Looks up whether the contact is blocked (off the main thread), then shows the sheet. */
    fun show(contact: ContactEntry) {
        val context = activity.applicationContext
        worker.execute {
            val blocked = runCatching {
                BlockedPeople.isBlocked(context, IdentityFingerprint.fromHex(contact.fingerprintHex))
            }.getOrDefault(false)
            // Naming v2: what this contact last called themselves (sealed, local), shown here and never as the title.
            val claim = runCatching { ContactClaims.store(context).get(IdentityFingerprint.fromHex(contact.fingerprintHex)) }.getOrNull()
            val hint = runCatching { hintFor(contact) }.getOrDefault(ContactReach.Hint.RECOGNISABLE)
            activity.runOnUiThread { if (!activity.isDestroyed) show(contact, blocked, claim, hint) }
        }
    }

    private fun show(contact: ContactEntry, blocked: Boolean, claim: ContactClaim?, hint: ContactReach.Hint) {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_contact, null)
        val dialog = BottomSheetDialog(activity)
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(contact.verifiedAtMs))
        val how = activity.getString(if (contact.viaQr) R.string.contacts_verified_qr else R.string.contacts_verified_code)
        view.findViewById<TextView>(R.id.contactSheetName).text = contact.title
        view.findViewById<TextView>(R.id.contactSheetDetail).text = activity.getString(
            R.string.contacts_detail,
            "$how, $date",
            contact.handle,
            IdentityFormat.groupedHex(contact.fingerprintHex),
        )
        view.findViewById<TextView>(R.id.contactRowBlockTitle)
            .setText(if (blocked) R.string.blocked_people_unblock else R.string.block_action)
        bindClaim(view, dialog, contact, claim)
        bindRecognitionHint(view, dialog, contact, hint)
        fun row(id: Int, action: () -> Unit) = view.findViewById<android.view.View>(id).setOnClickListener {
            dialog.dismiss()
            action()
        }
        row(R.id.contactRowVoice) { onCall(contact, false) }
        row(R.id.contactRowVideo) { onCall(contact, true) }
        onMessage()?.let { message ->
            view.findViewById<android.view.View>(R.id.contactRowMessage).visibility = android.view.View.VISIBLE
            row(R.id.contactRowMessage) { message(contact) }
        }
        row(R.id.contactRowRename) { rename(contact) }
        row(R.id.contactRowBlock) { blockOrUnblock(contact, blocked) }
        view.findViewById<android.view.View>(R.id.contactRowReport).visibility =
            if (DebugTunables.localReporting) android.view.View.VISIBLE else android.view.View.GONE
        row(R.id.contactRowReport) {
            CallBlockingUi.report(activity, IdentityFingerprint.fromHex(contact.fingerprintHex), onDone = onChanged)
        }
        row(R.id.contactRowRemove) { confirmForget(contact) }
        dialog.setContentView(view)
        dialog.show()
        openSheet = dialog
    }

    /** The sheet on screen, so an incoming call or the call screen can close it (F15). */
    private var openSheet: BottomSheetDialog? = null

    fun dismissOpen() {
        openSheet?.takeIf { it.isShowing }?.dismiss()
        openSheet = null
    }

    /**
     * "They call themselves ...", the one note when it changes ("Sunita now calls herself Sunita K": use it as your label, or
     * ignore), and "Scan to confirm" for a contact that was only checked by code. Your label stays the title either way.
     */
    private fun bindClaim(view: android.view.View, dialog: BottomSheetDialog, contact: ContactEntry, claim: ContactClaim?) {
        val claimText = view.findViewById<TextView>(R.id.contactSheetClaim)
        val useRow = view.findViewById<android.view.View>(R.id.contactRowUseClaim)
        val ignoreRow = view.findViewById<android.view.View>(R.id.contactRowIgnoreClaim)
        val name = claim?.claimedName?.takeIf { it.isNotEmpty() }
        if (name == null) {
            claimText.visibility = android.view.View.GONE
        } else if (claim.hasPendingChange) {
            claimText.visibility = android.view.View.VISIBLE
            claimText.text = activity.getString(R.string.contact_claim_changed, contact.title, name)
            useRow.visibility = android.view.View.VISIBLE
            ignoreRow.visibility = android.view.View.VISIBLE
            val fingerprint = IdentityFingerprint.fromHex(contact.fingerprintHex)
            useRow.setOnClickListener {
                dialog.dismiss()
                acknowledgeClaim(fingerprint)
                renameTo(contact, name)
            }
            ignoreRow.setOnClickListener {
                dialog.dismiss()
                acknowledgeClaim(fingerprint)
            }
        } else {
            claimText.visibility = android.view.View.VISIBLE
            claimText.text = activity.getString(R.string.contact_claim_calls_themselves, name)
        }
        if (claim?.scannedBothWays == true) {
            view.findViewById<TextView>(R.id.contactSheetDetail).append("\n" + activity.getString(R.string.contact_scanned_both_ways))
        }
        val confirm = view.findViewById<android.view.View>(R.id.contactRowScanConfirm)
        if (!contact.viaQr && NamingFeatures.bvmid2On()) {
            confirm.visibility = android.view.View.VISIBLE
            confirm.setOnClickListener {
                dialog.dismiss()
                activity.startActivity(VerifyContactActivity.intent(activity))
            }
        }
    }

    /**
     * A contact this phone cannot recognise nearby yet: "Ask <name> to scan your code too" (or scan again) with the Add contact
     * entry, so Contacts can call them afterwards. Nothing for a recognisable contact.
     */
    private fun bindRecognitionHint(view: android.view.View, dialog: BottomSheetDialog, contact: ContactEntry, hint: ContactReach.Hint) {
        val (note, action) = when (hint) {
            ContactReach.Hint.ASK_TO_SCAN -> activity.getString(R.string.contact_ask_scan_title, contact.title) to R.string.contact_show_my_code
            ContactReach.Hint.SCAN_AGAIN -> activity.getString(R.string.contact_not_recognised_title, contact.title) to R.string.contact_scan_again
            else -> return
        }
        view.findViewById<TextView>(R.id.contactSheetDetail).append("\n" + note)
        val row = view.findViewById<android.view.View>(R.id.contactRowScanConfirm)
        row.visibility = android.view.View.VISIBLE
        view.findViewById<TextView>(R.id.contactRowScanConfirmTitle).setText(action)
        row.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(VerifyContactActivity.intent(activity))
        }
    }

    private fun acknowledgeClaim(fingerprint: IdentityFingerprint) {
        val context = activity.applicationContext
        worker.execute { runCatching { ContactClaims.store(context).acknowledge(fingerprint) } }
    }

    private fun renameTo(contact: ContactEntry, name: String) {
        val context = activity.applicationContext
        worker.execute {
            val outcome = security.renameContact(context, contact.fingerprintHex, name)
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val message = when (outcome) {
                    RenameOutcome.RENAMED -> activity.getString(R.string.contact_saved, name)
                    RenameOutcome.NAME_TAKEN -> activity.getString(R.string.contact_name_taken)
                    RenameOutcome.INVALID_NAME -> activity.getString(R.string.contact_name_invalid)
                    else -> activity.getString(R.string.contact_gone)
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                onChanged()
            }
        }
    }

    private fun blockOrUnblock(contact: ContactEntry, blocked: Boolean) {
        val fingerprint = IdentityFingerprint.fromHex(contact.fingerprintHex)
        if (!blocked) {
            CallBlockingUi.confirmBlock(activity, fingerprint, contact.title)
            return
        }
        val context = activity.applicationContext
        worker.execute {
            BlockedPeople.unblock(context, fingerprint)
            activity.runOnUiThread {
                if (!activity.isDestroyed) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.blocked_people_unblocked, contact.title),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun rename(contact: ContactEntry) {
        ContactNameDialog.show(
            context = activity,
            title = R.string.contacts_rename_title,
            message = R.string.contacts_rename_message,
            initial = contact.name,
            allowBlank = false,
            positive = R.string.action_save,
            negative = R.string.action_cancel,
            onSave = { name ->
                val context = activity.applicationContext
                worker.execute {
                    val outcome = security.renameContact(context, contact.fingerprintHex, name)
                    activity.runOnUiThread {
                        if (activity.isDestroyed) return@runOnUiThread
                        val message = when (outcome) {
                            RenameOutcome.RENAMED -> activity.getString(R.string.contact_saved, name)
                            RenameOutcome.INVALID_NAME -> activity.getString(R.string.contact_name_invalid)
                            RenameOutcome.NAME_TAKEN -> activity.getString(R.string.contact_name_taken)
                            RenameOutcome.NOT_FOUND -> activity.getString(R.string.contact_gone)
                            RenameOutcome.STORE_UNREADABLE -> activity.getString(R.string.verify_result_store_unreadable)
                            RenameOutcome.UNAVAILABLE -> activity.getString(R.string.verify_unavailable)
                        }
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                        onChanged()
                    }
                }
            },
        )
    }

    private fun confirmForget(contact: ContactEntry) {
        MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.ic_warning)
            .setTitle(activity.getString(R.string.contacts_forget_title, contact.title))
            .setMessage(R.string.contacts_forget_message)
            .setPositiveButton(R.string.contacts_forget) { _, _ ->
                val context = activity.applicationContext
                worker.execute {
                    val forgotten = security.forgetContact(context, contact.fingerprintHex)
                    activity.runOnUiThread {
                        if (activity.isDestroyed) return@runOnUiThread
                        if (!forgotten) Toast.makeText(activity, R.string.contact_gone, Toast.LENGTH_LONG).show()
                        onChanged()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
