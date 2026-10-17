package com.example.bluetoothtest

/** A verified contact as the Contacts screen and the verify flow see it (hex strings, no crypto types). */
internal data class ContactEntry(
    val fingerprintHex: String,
    /** Your label for this contact; blank until you name them (the handle is shown instead). */
    val name: String,
    val verifiedAtMs: Long,
    val viaQr: Boolean,
) {
    val nodeIdHex: String get() = fingerprintHex.take(NodeId.BYTE_COUNT * 2)
    val prefixHex: String get() = fingerprintHex.take(DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2)

    /** The `XXXXX-XXXXX` handle every list shows for someone without a name. */
    val handle: String get() = IdentityHandle.fromPrefixHex(prefixHex)

    /** The one line that names this contact. */
    val title: String get() = name.ifBlank { handle }
}

/** What happened when a contact was renamed; each has its own plain-language message. */
internal enum class RenameOutcome { RENAMED, INVALID_NAME, NAME_TAKEN, NOT_FOUND, STORE_UNREADABLE, UNAVAILABLE }

/** Result of checking a name typed by the user (pure; unit tested). */
internal sealed class ContactNameCheck {
    /** Empty or only spaces: for a new contact this means "keep the handle". */
    object Blank : ContactNameCheck()

    /** [name] is the canonical text that would be saved (whitespace collapsed, NFC). */
    data class Valid(val name: String) : ContactNameCheck()

    /** Not allowed as a name; [reason] is a short internal reason, never shown raw. */
    data class Invalid(val reason: String) : ContactNameCheck()
}

internal object ContactNames {
    /** The same text rules as every other person name in the app ([LabelText], 32 bytes). */
    fun check(input: String): ContactNameCheck {
        if (input.isBlank()) return ContactNameCheck.Blank
        return try {
            ContactNameCheck.Valid(LabelText.normalize(LabelKind.PERSON_NAME, input))
        } catch (error: IllegalArgumentException) {
            ContactNameCheck.Invalid(error.message ?: "invalid")
        }
    }

    /**
     * What "Save contact as" starts with: the name you already gave this contact, else the name they
     * shared in this session (only a canonical one), else nothing - never a phone model or a handle.
     */
    fun suggestion(saved: String?, sharedBySession: String?): String {
        saved?.takeIf { it.isNotBlank() }?.let { return it }
        return (check(sharedBySession.orEmpty()) as? ContactNameCheck.Valid)?.name.orEmpty()
    }

    /** Named contacts first (A-Z, ignoring case), then the unnamed ones by handle. */
    fun sorted(contacts: List<ContactEntry>): List<ContactEntry> =
        contacts.sortedWith(
            compareBy<ContactEntry> { it.name.isBlank() }
                .thenBy { it.title.lowercase() }
                .thenBy { it.fingerprintHex },
        )
}
