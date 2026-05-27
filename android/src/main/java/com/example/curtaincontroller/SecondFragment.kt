package com.example.curtaincontroller

import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.curtaincontroller.databinding.FragmentSecondBinding
import kotlinx.coroutines.launch

class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!

    private val adapter = SettingsAdapter()

    private val prefs by lazy {
        requireContext().getSharedPreferences("curtain", Context.MODE_PRIVATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupIpField()
        setupScheduleUi()

        binding.recyclerSettings.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSettings.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        binding.recyclerSettings.adapter = adapter

        binding.buttonBack.setOnClickListener {
            findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }

        fetchOptions()
    }

    override fun onResume() {
        super.onResume()
        val ip = prefs.getString("esp_ip", "") ?: ""
        if (ip.isNotEmpty()) binding.editEspIp.setText(ip)
    }

    private fun setupIpField() {
        val saved = prefs.getString("esp_ip", "") ?: ""
        if (saved.isNotEmpty()) {
            EspClient.baseUrl = "http://$saved"
            binding.editEspIp.setText(saved)
        }
        binding.editEspIp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val ip = s?.toString()?.trim() ?: return
                if (ip.isNotEmpty()) {
                    prefs.edit().putString("esp_ip", ip).apply()
                    EspClient.baseUrl = "http://$ip"
                }
            }
        })
    }

    private fun setupScheduleUi() {
        val useManual = prefs.getBoolean("use_manual_times", false)
        if (useManual) {
            binding.radioManual.isChecked = true
            binding.groupManualTimes.visibility = View.VISIBLE
        } else {
            binding.radioApi.isChecked = true
            binding.groupManualTimes.visibility = View.GONE
        }

        binding.buttonOpenTime.text  = prefs.getString("manual_open",  "06:00")
        binding.buttonCloseTime.text = prefs.getString("manual_close", "20:00")

        binding.radioTimeMode.setOnCheckedChangeListener { _, checkedId ->
            val isManual = (checkedId == R.id.radio_manual)
            binding.groupManualTimes.visibility = if (isManual) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("use_manual_times", isManual).apply()
        }

        binding.buttonOpenTime.setOnClickListener  { showTimePicker(isOpen = true) }
        binding.buttonCloseTime.setOnClickListener { showTimePicker(isOpen = false) }

        binding.buttonSaveTimes.setOnClickListener {
            lifecycleScope.launch {
                if (prefs.getBoolean("use_manual_times", false)) {
                    val open  = prefs.getString("manual_open",  "06:00") ?: "06:00"
                    val close = prefs.getString("manual_close", "20:00") ?: "20:00"
                    EspClient.setSunTimes(open, close)
                } else {
                    EspClient.fetchAndPushSunTimes()
                }
            }
        }
    }

    private fun showTimePicker(isOpen: Boolean) {
        val prefKey = if (isOpen) "manual_open" else "manual_close"
        val default = if (isOpen) "06:00" else "20:00"
        val saved   = prefs.getString(prefKey, default) ?: default
        val parts   = saved.split(":")
        val hour    = parts[0].toIntOrNull() ?: if (isOpen) 6 else 20
        val minute  = parts[1].toIntOrNull() ?: 0

        TimePickerDialog(requireContext(), { _, h, m ->
            val formatted = "%02d:%02d".format(h, m)
            prefs.edit().putString(prefKey, formatted).apply()
            if (isOpen) binding.buttonOpenTime.text  = formatted
            else        binding.buttonCloseTime.text = formatted
        }, hour, minute, true).show()
    }

    private fun fetchOptions() {
        binding.settingsError.visibility  = View.GONE
        binding.settingsLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            EspClient.getOptions().fold(
                onSuccess = { options ->
                    binding.settingsLoading.visibility = View.GONE
                    adapter.update(options.entries.map { it.key to it.value })
                },
                onFailure = { err ->
                    binding.settingsLoading.visibility = View.GONE
                    binding.settingsError.visibility   = View.VISIBLE
                    binding.settingsError.text = err.message ?: getString(R.string.error_connection)
                }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
