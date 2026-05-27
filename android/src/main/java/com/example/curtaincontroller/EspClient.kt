package com.example.curtaincontroller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class CurtainStatus(
    val positionStart: Int,
    val positionEnd: Int,
    val currentPosition: Int,
    val state: String,
    val sunrise: String,
    val sunset: String,
)

object EspClient {
    var baseUrl = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun getStatus(): Result<CurtainStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(get("/status"))
            CurtainStatus(
                positionStart   = json.getInt("position_start"),
                positionEnd     = json.getInt("position_end"),
                currentPosition = json.getInt("current_position"),
                state           = json.getString("state"),
                sunrise         = json.getString("sunrise"),
                sunset          = json.getString("sunset"),
            )
        }
    }

    suspend fun getOptions(): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(get("/state"))
            json.keys().asSequence().associateWith { json.opt(it)?.toString() ?: "" }
        }
    }

    suspend fun fetchAndPushSunTimes(
        lat: Double = 42.439663,
        lon: Double = 26.096306,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tzid = TimeZone.getDefault().getID()
            val apiUrl = "https://api.sunrise-sunset.org/json?lat=$lat&lng=$lon" +
                "&tzid=${URLEncoder.encode(tzid, "UTF-8")}"
            val body = http.newCall(Request.Builder().url(apiUrl).build()).execute().use { resp ->
                check(resp.isSuccessful) { "Sunrise API HTTP ${resp.code}" }
                resp.body?.string() ?: error("empty body")
            }
            val results = JSONObject(body).getJSONObject("results")
            val fmt12 = SimpleDateFormat("h:mm:ss a", Locale.US)
            val fmt24 = SimpleDateFormat("HH:mm", Locale.US)
            val sunrise = fmt24.format(fmt12.parse(results.getString("sunrise"))!!)
            val sunset  = fmt24.format(fmt12.parse(results.getString("sunset"))!!)
            val path = "/set_sunrise_sunset" +
                "?sunrise=${URLEncoder.encode(sunrise, "UTF-8")}" +
                "&sunset=${URLEncoder.encode(sunset,  "UTF-8")}"
            get(path)
            Unit
        }
    }

    suspend fun setSunTimes(sunrise: String, sunset: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/set_sunrise_sunset" +
                "?sunrise=${URLEncoder.encode(sunrise, "UTF-8")}" +
                "&sunset=${URLEncoder.encode(sunset,  "UTF-8")}"
            get(path)
            Unit
        }
    }

    suspend fun move(position: Int)    = command("/motor_goto?target=$position")
    suspend fun manualStep(steps: Int) = command("/motor_manual_step?steps=$steps")
    suspend fun setStart()             = command("/set_start")
    suspend fun goStart()              = command("/motor_goto?target=0")
    suspend fun setEnd()               = command("/set_end")
    suspend fun goEnd()                = command("/motor_goto?target=100")

    private suspend fun command(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { get(path); Unit }
    }

    private fun get(path: String): String {
        val req = Request.Builder().url("$baseUrl$path").build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            return resp.body?.string() ?: error("empty body")
        }
    }
}
