package com.example.curtaincontroller

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.curtaincontroller.databinding.ItemSettingBinding

class SettingsAdapter(private var items: List<Pair<String, String>> = emptyList()) :
    RecyclerView.Adapter<SettingsAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemSettingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(key: String, value: String) {
            binding.textKey.text = key
            binding.textValue.text = value
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSettingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (key, value) = items[position]
        holder.bind(key, value)
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<Pair<String, String>>) {
        items = newItems
        notifyDataSetChanged()
    }
}
