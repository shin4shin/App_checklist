package com.example.homeworktracker

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SyncClient {
    var baseUrl: String = "https://events.yulda.kr"

    class UnauthorizedException : Exception("인증이 만료되었습니다")

    data class UserInfo(val id: String, val email: String)
    data class AuthResponse(val token: String, val user: UserInfo)
    data class SyncResponse(val serverTasks: JSONArray, val serverSettings: JSONArray, val syncedAt: Long)
    data class PairingCodeResponse(val code: String, val rawCode: String, val expiresAt: Long)

    fun loginAsDevice(deviceId: String): AuthResponse {
        val url = URL("$baseUrl/v1/auth/device")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")

            val payload = JSONObject().put("deviceId", deviceId).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            check(code == 200) { "디바이스 등록 실패 ($code): $responseText" }

            val json = JSONObject(responseText)
            val token = json.getString("token")
            val userObj = json.getJSONObject("user")
            return AuthResponse(token, UserInfo(userObj.getString("id"), "device@dailycheck.app"))
        } finally {
            conn.disconnect()
        }
    }

    fun generatePairingCode(token: String): PairingCodeResponse {
        val url = URL("$baseUrl/v1/auth/pair/code")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code == 401) throw UnauthorizedException()
            check(code == 200) { "연동 코드 발급 실패 ($code): $responseText" }

            val json = JSONObject(responseText)
            return PairingCodeResponse(
                code = json.getString("code"),
                rawCode = json.getString("rawCode"),
                expiresAt = json.getLong("expiresAt")
            )
        } finally {
            conn.disconnect()
        }
    }

    fun loginWithGoogle(idToken: String): AuthResponse {
        val url = URL("$baseUrl/v1/auth/google")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")

            val payload = JSONObject().put("idToken", idToken).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            check(code == 200) { "로그인 실패 ($code): $responseText" }

            val json = JSONObject(responseText)
            val token = json.getString("token")
            val userObj = json.getJSONObject("user")
            return AuthResponse(token, UserInfo(userObj.getString("id"), userObj.getString("email")))
        } finally {
            conn.disconnect()
        }
    }

    fun sync(token: String, tasks: JSONArray, settings: JSONArray, since: Long): SyncResponse {
        val url = URL("$baseUrl/v1/sync")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")

            val payload = JSONObject()
                .put("since", since)
                .put("tasks", tasks)
                .put("settings", settings)
                .toString()

            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            check(code == 200) { "동기화 실패 ($code): $responseText" }

            val json = JSONObject(responseText)
            val serverChanges = json.optJSONObject("serverChanges") ?: JSONObject()
            val serverTasks = serverChanges.optJSONArray("tasks") ?: JSONArray()
            val serverSettings = serverChanges.optJSONArray("settings") ?: JSONArray()
            val syncedAt = json.optLong("syncedAt", System.currentTimeMillis())

            return SyncResponse(serverTasks, serverSettings, syncedAt)
        } finally {
            conn.disconnect()
        }
    }
}
