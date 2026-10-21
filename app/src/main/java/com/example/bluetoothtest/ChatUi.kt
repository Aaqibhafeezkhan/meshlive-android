package com.example.bluetoothtest

import android.content.Context

/**
 * The chat entry (user feedback 2026-10-21: "just a Messages button on the Live page"): a Chats tab in the bottom
 * navigation after Calls, present only with the Experimental messages switch on. UI and navigation only; the engine, the
 * store and the protocol are unchanged.
 */
internal object ChatTab {
    /** The tab exists only while the messages switch is on; off, the navigation is exactly as before. */
    fun visible(messagesOn: Boolean): Boolean = messagesOn

    /** The tab's badge: the unread messages of every chat, or null for no badge. */
    fun badge(unreadPerChat: Collection<Int>): Int? = unreadPerChat.sumOf { it.coerceAtLeast(0) }.takeIf { it > 0 }

    /** With Messaging v2 the Chats tab lists conversations in place; with the older Messages only, it opens that screen. */
    fun inPlace(v2: Boolean): Boolean = v2
}

/** Which thread an entry point opens: a contact row's or detail's message button, the call-ended "Message", a notification. */
internal object ChatEntry {
    enum class Source { CONTACT_ROW, CONTACT_DETAIL, CALL_ENDED, NOTIFICATION }

    /** The node ID (hex) of the thread to open, or null when there is no chat to open (feature off, no identity). */
    fun threadFor(source: Source, nodeIdHex: String?, v2: Boolean): String? =
        nodeIdHex?.lowercase()?.takeIf { v2 && it.length == NODE_ID_HEX && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private const val NODE_ID_HEX = NodeId.BYTE_COUNT * 2
}

/** The composer's action button: Send only with text; an empty field offers the voice note (Send is never pressable empty). */
internal object ComposerAction {
    enum class Action { SEND, VOICE }

    fun forText(text: CharSequence?): Action = if (text?.isNotBlank() == true) Action.SEND else Action.VOICE
}

/** Muted chats (a local list in app preferences): a muted chat keeps its unread count but posts no notification. */
internal object ChatMute {
    private const val PREFS = "chat_mute"
    private const val KEY = "muted"

    fun isMuted(context: Context, peerHex: String): Boolean =
        peerHex.lowercase() in context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()

    fun setMuted(context: Context, peerHex: String, muted: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = toggled(prefs.getStringSet(KEY, emptySet()).orEmpty(), peerHex, muted)
        prefs.edit().putStringSet(KEY, next).apply()
    }

    fun toggled(current: Set<String>, peerHex: String, muted: Boolean): Set<String> =
        if (muted) current + peerHex.lowercase() else current - peerHex.lowercase()
}
