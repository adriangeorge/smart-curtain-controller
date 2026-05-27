package com.example.curtaincontroller

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.curtaincontroller.databinding.FragmentFirstBinding
import kotlinx.coroutines.launch

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    companion object {
        private var sessionSyncDone = false
    }

    private lateinit var mdns: MdnsDiscovery

    private val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            binding.textSliderPercent.text = "$progress%"
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {
            lifecycleScope.launch {
                EspClient.move(seekBar.progress)
                fetchStatus()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("curtain", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("esp_ip", "") ?: ""
        if (savedIp.isNotEmpty()) EspClient.baseUrl = "http://$savedIp"

        mdns = MdnsDiscovery(requireContext())
        mdns.start { ip ->
            Handler(Looper.getMainLooper()).post {
                if (ip != prefs.getString("esp_ip", "")) {
                    prefs.edit().putString("esp_ip", ip).apply()
                    EspClient.baseUrl = "http://$ip"
                }
            }
        }

        binding.sliderPosition.setOnSeekBarChangeListener(seekBarListener)

        binding.buttonSettings.setOnClickListener {
            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
        }
        binding.buttonRefresh.setOnClickListener { fetchStatus() }

        binding.buttonSetHome.setOnClickListener {
            lifecycleScope.launch { EspClient.setStart(); fetchStatus() }
        }
        binding.buttonGoHome.setOnClickListener {
            lifecycleScope.launch { EspClient.goStart(); fetchStatus() }
        }
        binding.buttonSetEnd.setOnClickListener {
            lifecycleScope.launch { EspClient.setEnd(); fetchStatus() }
        }
        binding.buttonGoEnd.setOnClickListener {
            lifecycleScope.launch { EspClient.goEnd(); fetchStatus() }
        }

        binding.buttonStepBack.setOnClickListener {
            val steps = binding.editStepCount.text.toString().toIntOrNull() ?: 100
            lifecycleScope.launch { EspClient.manualStep(-steps); fetchStatus() }
        }
        binding.buttonStepForward.setOnClickListener {
            val steps = binding.editStepCount.text.toString().toIntOrNull() ?: 100
            lifecycleScope.launch { EspClient.manualStep(steps); fetchStatus() }
        }

        binding.buttonSyncSun.setOnClickListener {
            lifecycleScope.launch { syncTimes(); fetchStatus() }
        }

        if (!sessionSyncDone) {
            sessionSyncDone = true
            lifecycleScope.launch { syncTimes() }
        }
        fetchStatus()
    }

    private suspend fun syncTimes() {
        val prefs = requireContext().getSharedPreferences("curtain", Context.MODE_PRIVATE)
        if (prefs.getBoolean("use_manual_times", false)) {
            val open  = prefs.getString("manual_open",  "06:00") ?: "06:00"
            val close = prefs.getString("manual_close", "20:00") ?: "20:00"
            EspClient.setSunTimes(open, close)
        } else {
            EspClient.fetchAndPushSunTimes()
        }
    }

    private fun fetchStatus() {
        binding.statusError.visibility = View.GONE
        binding.dotRefresh.visibility = View.VISIBLE

        lifecycleScope.launch {
            EspClient.getStatus().fold(
                onSuccess = { status ->
                    binding.dotRefresh.visibility = View.GONE
                    binding.textPosition.text = "${status.positionStart} → ${status.positionEnd} steps"
                    binding.textState.text = status.state
                    binding.textSunrise.text = status.sunrise
                    binding.textSunset.text = status.sunset
                    val range = status.positionEnd - status.positionStart
                    val pct = if (range != 0) {
                        ((status.currentPosition - status.positionStart).toFloat() / range * 100)
                            .toInt().coerceIn(0, 100)
                    } else 0
                    binding.textMotorPos.text = "$pct% (${status.currentPosition} steps)"
                    binding.sliderPosition.progress = pct
                    binding.textSliderPercent.text = "$pct%"
                },
                onFailure = { err ->
                    binding.dotRefresh.visibility = View.GONE
                    binding.statusError.visibility = View.VISIBLE
                    binding.statusError.text = err.message ?: getString(R.string.error_connection)
                }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mdns.stop()
        _binding = null
    }
}
