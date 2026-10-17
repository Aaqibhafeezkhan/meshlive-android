package com.example.bluetoothtest

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * The Contacts tab rows (user request 2026-10-17): the name, "Nearby now" when in range, and video call, voice call
 * and info buttons. Tapping the row or the info button opens the contact sheet ([onInfo]).
 */
internal class ContactRowAdapter(
    private val context: Context,
    private val contacts: List<ContactEntry>,
    private val inRange: Set<String>,
    private val onCall: (ContactEntry, video: Boolean) -> Unit,
    private val onInfo: (ContactEntry) -> Unit,
) : BaseAdapter() {
    override fun getCount(): Int = contacts.size

    override fun getItem(position: Int): ContactEntry = contacts[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_contact_row, parent, false)
        val contact = contacts[position]
        view.findViewById<TextView>(R.id.contactRowTitle).text = contact.title
        view.findViewById<TextView>(R.id.contactRowSubtitle).visibility =
            if (contact.prefixHex in inRange) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.contactRowSubtitle).setText(R.string.contacts_nearby_now)
        view.contentDescription = context.getString(R.string.contacts_row_description, contact.title)
        view.findViewById<View>(R.id.contactRowVideoButton).setOnClickListener { onCall(contact, true) }
        view.findViewById<View>(R.id.contactRowVoiceButton).setOnClickListener { onCall(contact, false) }
        view.findViewById<View>(R.id.contactRowInfoButton).setOnClickListener { onInfo(contact) }
        return view
    }
}
