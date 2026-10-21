package com.example.bluetoothtest

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * The Contacts tab rows (user request 2026-10-17): the name, "Nearby now" when in range (else what to do when this phone
 * cannot recognise the contact yet, [hints]), and video call, voice call and info buttons. Tapping the row or the info
 * button opens the contact sheet ([onInfo]).
 */
internal class ContactRowAdapter(
    private val context: Context,
    private val contacts: List<ContactEntry>,
    private val inRange: Set<String>,
    private val hints: Map<String, ContactReach.Hint>,
    private val onCall: (ContactEntry, video: Boolean) -> Unit,
    private val onInfo: (ContactEntry) -> Unit,
    /** A message button on the row (only with the chat UI on, [ChatEntry]); null hides it. */
    private val onMessage: ((ContactEntry) -> Unit)? = null,
) : BaseAdapter() {
    override fun getCount(): Int = contacts.size

    override fun getItem(position: Int): ContactEntry = contacts[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_contact_row, parent, false)
        val contact = contacts[position]
        view.findViewById<TextView>(R.id.contactRowTitle).text = contact.title
        val subtitle = when {
            contact.prefixHex in inRange -> R.string.contacts_nearby_now
            hints[contact.prefixHex] == ContactReach.Hint.ASK_TO_SCAN -> R.string.contact_row_ask_scan
            hints[contact.prefixHex] == ContactReach.Hint.SCAN_AGAIN -> R.string.contact_row_scan_again
            else -> null
        }
        view.findViewById<TextView>(R.id.contactRowSubtitle).apply {
            visibility = if (subtitle != null) View.VISIBLE else View.GONE
            if (subtitle != null) setText(subtitle)
        }
        view.contentDescription = context.getString(R.string.contacts_row_description, contact.title)
        view.findViewById<View>(R.id.contactRowVideoButton).setOnClickListener { onCall(contact, true) }
        view.findViewById<View>(R.id.contactRowVoiceButton).setOnClickListener { onCall(contact, false) }
        view.findViewById<View>(R.id.contactRowInfoButton).setOnClickListener { onInfo(contact) }
        view.findViewById<View>(R.id.contactRowMessageButton).apply {
            visibility = if (onMessage != null) View.VISIBLE else View.GONE
            setOnClickListener { onMessage?.invoke(contact) }
        }
        return view
    }
}
