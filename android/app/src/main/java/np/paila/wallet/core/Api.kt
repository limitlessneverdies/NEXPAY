package np.paila.wallet.core

import np.paila.wallet.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class ApiError(val code: String, message: String, val status: Int) : Exception(message)
object Api {
    fun serverUrl(input: String): String {
        val u = URI(input.trim().trimEnd('/'))
        require(u.host != null && u.userInfo == null && u.query == null && u.fragment == null && (u.path.isNullOrEmpty() || u.path == "/")) { "Enter the server origin, for example https://pay.your-domain.com" }
        val debugLocal = BuildConfig.DEBUG && u.scheme == "http" && u.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
        require(u.scheme == "https" || debugLocal) { "Use a public HTTPS server. HTTP is allowed only for local debug builds." }
        require(u.port == -1 || u.port in 1..65535) { "Invalid server port" }
        return u.toString().trimEnd('/')
    }
    fun call(base: String, path: String, envelope: String? = null): JSONObject {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (envelope != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = JSONObject().put("envelope", envelope).toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            require(code !in 300..399) { "Server redirects are not accepted. Enter the final HTTPS origin." }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (out.size() <= 1_048_576) { val n = input.read(buffer, 0, minOf(buffer.size, 1_048_577 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: byteArrayOf()
            require(bytes.size <= 1_048_576) { "Server response too large" }
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { throw ApiError("INVALID_RESPONSE", "Server returned an invalid response", code) }
            if (code !in 200..299) { val e = json.optJSONObject("error"); throw ApiError(e?.optString("code") ?: "SERVER_ERROR", e?.optString("message") ?: "Server request failed", code) }
            return json
        } finally { connection.disconnect() }
    }
}
