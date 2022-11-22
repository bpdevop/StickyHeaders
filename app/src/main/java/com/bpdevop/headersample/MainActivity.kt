package com.bpdevop.headersample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bpdevop.headersample.adapters.AgendaAdapter
import com.bpdevop.headersample.databinding.ActivityMainBinding
import com.bpdevop.headersample.model.Agenda

class MainActivity : AppCompatActivity() {
    private val sadapter = AgendaAdapter()
    //private val sadapter = SimpleAgendaAdapter()

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setRecyclerview()

        val agenda: MutableList<Agenda> = mutableListOf()
        agenda.add(Agenda("Prueba1", "", "19/11/2021"))
        agenda.add(Agenda("Prueba2", "", "19/11/2021"))
        agenda.add(Agenda("Prueba3", "", "19/11/2021"))
        agenda.add(Agenda("Prueba4", "", "19/11/2021"))
        agenda.add(Agenda("Prueba5", "", "19/11/2021"))
        agenda.add(Agenda("Prueba6", "", "19/11/2021"))

        binding.rvDatos.apply {
            adapter = sadapter
            layoutManager = LinearLayoutManager(context)
        }

        binding.rvDatos.post { sadapter.setPeople(agenda) }
        //sadapter.differ.submitList(agenda)
    }

    private fun setRecyclerview() {

    }

}