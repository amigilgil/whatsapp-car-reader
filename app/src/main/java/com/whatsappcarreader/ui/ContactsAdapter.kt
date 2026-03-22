package com.whatsappcarreader.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.whatsappcarreader.R
import com.whatsappcarreader.model.Contact

class ContactsAdapter(
    private val onContactClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactsAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Contact>() {
            override fun areItemsTheSame(a: Contact, b: Contact) = a.phoneOrName == b.phoneOrName
            override fun areContentsTheSame(a: Contact, b: Contact) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardContact)
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val name: TextView = view.findViewById(R.id.tvContactName)
        val voiceStatus: TextView = view.findViewById(R.id.tvVoiceStatus)
        val sampleSeconds: TextView = view.findViewById(R.id.tvSampleSeconds)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = getItem(position)
        val ctx = holder.itemView.context

        // Avatar circle
        holder.avatar.text = contact.displayName.firstOrNull()?.uppercase() ?: "?"
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(contact.avatarColor)
        }
        holder.avatar.background = circle

        holder.name.text = contact.displayName

        // Voice status
        if (contact.elevenLabsVoiceId != null) {
            holder.voiceStatus.text = "🎙️ קול משובט"
            holder.voiceStatus.setTextColor(ctx.getColor(R.color.wa_green))
        } else {
            val needed = maxOf(0f, 30f - contact.voiceSamplesSeconds)
            holder.voiceStatus.text = if (contact.voiceSamplesSeconds > 0)
                "⏳ צובר דוגמאות קול"
            else
                "📢 קול ברירת מחדל"
            holder.voiceStatus.setTextColor(ctx.getColor(R.color.text_muted))
        }

        holder.sampleSeconds.text = "${contact.voiceSamplesSeconds.toInt()}s הוקלטו"

        holder.card.setOnClickListener { onContactClick(contact) }
    }
}
