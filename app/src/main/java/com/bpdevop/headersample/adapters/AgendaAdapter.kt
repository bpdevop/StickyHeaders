package com.bpdevop.headersample.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.bpdevop.headersample.R
import com.bpdevop.headersample.model.Agenda
import com.bpdevop.stickyheader.StickyAdapter

class AgendaAdapter : StickyAdapter() {

    data class Section(var fecha: String = "", val agenda: MutableList<Agenda> = arrayListOf())

    class ItemViewHolder internal constructor(itemView: View) : StickyAdapter.ItemViewHolder(itemView) {
        var personNameTextView: TextView

        init {
            personNameTextView = itemView.findViewById(R.id.tvNombre)
        }
    }

    class HeaderViewHolder internal constructor(itemView: View) : StickyAdapter.HeaderViewHolder(itemView) {
        var titleTextView: TextView

        init {
            titleTextView = itemView.findViewById(R.id.tvFecha)
        }
    }

    private var people: List<Agenda>? = null
    private val sections: ArrayList<Section> = ArrayList()

    fun setPeople(listAgenda: List<Agenda>) {
        this.people = listAgenda
        sections.clear()

        // sort people into buckets by the first letter of last name
        var alpha = ""
        var currentSection: Section? = null
        for (agenda in listAgenda) {
            if (agenda.fecha !== alpha) {
                if (currentSection != null) {
                    sections.add(currentSection)
                }
                currentSection = Section()
                alpha = agenda.fecha
                currentSection.fecha = alpha
            }
            currentSection?.agenda?.add(agenda)
        }
        currentSection?.let { sections.add(it) }
        notifyAllSectionsDataSetChanged()
    }

    override fun getNumberOfSections(): Int = sections.size

    override fun getNumberOfItemsInSection(sectionIndex: Int): Int = sections[sectionIndex].agenda.size

    override fun doesSectionHaveHeader(sectionIndex: Int): Boolean = true

    override fun doesSectionHaveFooter(sectionIndex: Int): Boolean = false

    override fun onCreateItemViewHolder(parent: ViewGroup?, itemUserType: Int): StickyAdapter.ItemViewHolder {
        val inflater = LayoutInflater.from(parent!!.context)
        val v: View = inflater.inflate(R.layout.agenda_item, parent, false)
        return ItemViewHolder(v)
    }

    override fun onCreateHeaderViewHolder(parent: ViewGroup?, headerUserType: Int): StickyAdapter.HeaderViewHolder {
        val inflater = LayoutInflater.from(parent!!.context)
        val v: View = inflater.inflate(R.layout.agenda_header, parent, false)
        return HeaderViewHolder(v)
    }

    override fun onBindItemViewHolder(viewHolder: StickyAdapter.ItemViewHolder?, sectionIndex: Int, itemIndex: Int, itemUserType: Int) {
        val s: Section = sections[sectionIndex]
        val ivh: ItemViewHolder = viewHolder as ItemViewHolder
        val agenda: Agenda = s.agenda[itemIndex]
        ivh.personNameTextView.text = agenda.nombre
    }

    override fun onBindHeaderViewHolder(viewHolder: StickyAdapter.HeaderViewHolder?, sectionIndex: Int, headerUserType: Int) {
        val s: Section = sections[sectionIndex]
        val hvh: HeaderViewHolder = viewHolder as HeaderViewHolder

        hvh.titleTextView.text = s.fecha
    }

}
