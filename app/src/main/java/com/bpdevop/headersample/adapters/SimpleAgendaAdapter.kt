package com.bpdevop.headersample.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bpdevop.headersample.R
import com.bpdevop.headersample.databinding.AgendaItemBinding
import com.bpdevop.headersample.model.Agenda

class SimpleAgendaAdapter : RecyclerView.Adapter<SimpleAgendaAdapter.ViewHolder>() {
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)


    private val differCallback = object : DiffUtil.ItemCallback<Agenda>() {
        override fun areItemsTheSame(oldItem: Agenda, newItem: Agenda): Boolean {
            return oldItem.hashCode() == newItem.hashCode()
        }

        override fun areContentsTheSame(oldItem: Agenda, newItem: Agenda): Boolean {
            return oldItem == newItem
        }
    }

    val differ = AsyncListDiffer(this, differCallback)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.agenda_item, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = AgendaItemBinding.bind(holder.itemView)
        val agenda = differ.currentList[position]
        with(binding) {
            tvNombre.text = agenda.nombre
            tvDescripcioin.text = agenda.descripcion
        }
    }

    override fun getItemCount(): Int = differ.currentList.size


}