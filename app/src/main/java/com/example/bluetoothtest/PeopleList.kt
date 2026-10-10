package com.example.bluetoothtest

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/** One entry of the "People nearby" call list. */
internal sealed class PeopleItem {
    data class Header(val title: String) : PeopleItem()

    data class Person(
        /** Stable key (node-ID prefix hex) the activity resolves back to the discovered peer. */
        val key: String,
        val name: String,
        /** "Nearby" or "Via 2 phones". */
        val reach: String,
        val relayed: Boolean,
        val avatarColor: Int,
        val showVoice: Boolean,
    ) : PeopleItem()
}

/**
 * Contacts first, then everyone else nearby, each sorted by name. [contactNames] maps node-ID
 * prefixes to the names saved when verifying; the advert itself is not proof (identity is checked
 * when the call connects), so contacts get a section, not a "Verified" badge.
 */
internal fun peopleSections(
    people: List<PeopleItem.Person>,
    contactKeys: Set<String>,
    contactsTitle: String,
    nearbyTitle: String,
): List<PeopleItem> {
    val (contacts, others) = people.partition { it.key in contactKeys }
    return buildList {
        if (contacts.isNotEmpty()) {
            add(PeopleItem.Header(contactsTitle))
            addAll(contacts.sortedBy { it.name.lowercase() })
        }
        if (others.isNotEmpty()) {
            add(PeopleItem.Header(nearbyTitle))
            addAll(others.sortedBy { it.name.lowercase() })
        }
    }
}

/**
 * Updated in place ([submit]) so the continuous refresh does not reset scrolling or flicker.
 * Taps on a row go through the ListView's item listener ([personAt]); the call buttons call
 * [onCall] with the person's key and whether it is a video call.
 */
internal class PeopleAdapter(
    private val context: Context,
    private val onCall: (key: String, video: Boolean) -> Unit,
) : BaseAdapter() {
    private var items: List<PeopleItem> = emptyList()

    fun submit(newItems: List<PeopleItem>) {
        if (newItems == items) return
        items = newItems
        notifyDataSetChanged()
    }

    fun personAt(position: Int): PeopleItem.Person? = items.getOrNull(position) as? PeopleItem.Person

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): PeopleItem = items[position]
    override fun getItemId(position: Int): Long = when (val item = items[position]) {
        is PeopleItem.Header -> item.title.hashCode().toLong()
        is PeopleItem.Person -> item.key.hashCode().toLong()
    }
    override fun hasStableIds(): Boolean = true
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (items[position] is PeopleItem.Header) 0 else 1
    override fun isEnabled(position: Int): Boolean = items[position] is PeopleItem.Person
    override fun areAllItemsEnabled(): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val item = items[position]) {
            is PeopleItem.Header -> {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_section_header, parent, false)
                (view as TextView).text = item.title
                view
            }
            is PeopleItem.Person -> bindPerson(
                convertView ?: LayoutInflater.from(context).inflate(R.layout.item_person_row, parent, false),
                item,
            )
        }

    private fun bindPerson(view: View, person: PeopleItem.Person): View {
        view.findViewById<TextView>(R.id.personAvatar).apply {
            text = person.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
            backgroundTintList = ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, R.color.surface_variant))
        }
        view.findViewById<TextView>(R.id.personName).text = person.name
        view.findViewById<TextView>(R.id.personReach).apply {
            text = person.reach
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (person.relayed) R.drawable.ic_relay_small else 0, 0, 0, 0)
        }
        view.findViewById<View>(R.id.personVoiceButton).apply {
            visibility = if (person.showVoice) View.VISIBLE else View.GONE
            contentDescription = context.getString(R.string.people_voice_call, person.name)
            setOnClickListener { onCall(person.key, false) }
        }
        view.findViewById<View>(R.id.personVideoButton).apply {
            contentDescription = context.getString(R.string.people_video_call, person.name)
            setOnClickListener { onCall(person.key, true) }
        }
        view.contentDescription = context.getString(R.string.peer_row_description, person.name, person.reach)
        return view
    }
}
