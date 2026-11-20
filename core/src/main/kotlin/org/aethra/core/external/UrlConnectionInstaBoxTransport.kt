package org.aethra.core.external

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android/JVM-compatible HTTP transport for the InstaBox API.
 *
 * It deliberately contains no credential storage; the provider supplies the API key
 * per request. The default base URL is the official InstaBox API endpoint.
 */
class UrlConnectionInstaBoxTransport(
    private val baseUrl: String = "https://api.instabox.sh"
) : InstaBoxTransport {
    override suspend fun request(
        method: String,
        path: String,
        body: String?,
        headers: Map<String, String>
    ): InstaBoxHttpResponse = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            InstaBoxHttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}
