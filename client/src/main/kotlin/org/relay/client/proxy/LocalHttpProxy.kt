package org.relay.client.proxy

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import org.relay.client.config.ClientConfig
import org.relay.shared.protocol.ResponsePayload
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

@ApplicationScoped
class LocalHttpProxy @Inject constructor(
    private val clientConfig: ClientConfig
) {
    private val logger = LoggerFactory.getLogger(LocalHttpProxy::class.java)
    
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(30))
        .writeTimeout(Duration.ofSeconds(30))
        .build()
    
    /**
     * Proxy an HTTP request to the local application using a RequestPayload.
     * 
     * @param requestPayload the request payload containing all request details
     * @return ResponsePayload containing the response from the local application
     */
    fun proxyRequest(requestPayload: org.relay.shared.protocol.RequestPayload): ResponsePayload {
        return proxyRequest(
            requestPayload.method,
            requestPayload.path,
            requestPayload.query,
            requestPayload.headers,
            requestPayload.body
        )
    }
    
    /**
     * Proxy an HTTP request to the local application.
     * 
     * @param method HTTP method (GET, POST, etc.)
     * @param path Request path
     * @param query Query parameters (optional)
     * @param headers Request headers
     * @param body Base64-encoded request body (optional)
     * @return ResponsePayload containing the response from the local application
     */
    fun proxyRequest(
        method: String,
        path: String,
        query: Map<String, String>?,
        headers: Map<String, String>,
        body: String?
    ): ResponsePayload {
        val localUrl = clientConfig.localUrl()
        
        return try {
            // Build the full URL
            val baseUrl = localUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid local URL: $localUrl")
            
            val urlBuilder = baseUrl.newBuilder()
                .encodedPath(path)
            
            // Add query parameters
            query?.forEach { (name, value) ->
                urlBuilder.addQueryParameter(name, value)
            }
            
            val url = urlBuilder.build()
            
            // Build request body
            val requestBody = body?.let {
                val decodedBody = Base64.getDecoder().decode(it)
                val contentType = headers["Content-Type"] ?: "application/octet-stream"
                decodedBody.toRequestBody(contentType.toMediaTypeOrNull())
            }
            
            // Build request
            val requestBuilder = Request.Builder()
                .url(url)
                .method(method, requestBody)
            
            // Add headers (filter hop-by-hop headers)
            val filteredHeaders = headers.filter { (name, _) ->
                !name.equals("host", ignoreCase = true) &&
                !name.equals("connection", ignoreCase = true) &&
                !name.equals("keep-alive", ignoreCase = true) &&
                !name.equals("proxy-authenticate", ignoreCase = true) &&
                !name.equals("proxy-authorization", ignoreCase = true) &&
                !name.equals("te", ignoreCase = true) &&
                !name.equals("transfer-encoding", ignoreCase = true) &&
                !name.equals("upgrade", ignoreCase = true)
            }
            
            filteredHeaders.forEach { (name, value) ->
                requestBuilder.header(name, value)
            }
            
            val request = requestBuilder.build()
            
            logger.debug("Proxying {} request to {}", method, url)
            
            // Execute request
            httpClient.newCall(request).execute().use { response ->
                convertToPayload(response)
            }
            
        } catch (e: IOException) {
            logger.error("Failed to proxy request to local application", e)
            ResponsePayload(
                statusCode = 502,
                headers = mapOf("Content-Type" to "text/plain"),
                body = Base64.getEncoder().encodeToString("Bad Gateway: ${e.message}".toByteArray())
            )
        } catch (e: Exception) {
            logger.error("Unexpected error while proxying request", e)
            ResponsePayload(
                statusCode = 500,
                headers = mapOf("Content-Type" to "text/plain"),
                body = Base64.getEncoder().encodeToString("Internal Server Error: ${e.message}".toByteArray())
            )
        }
    }
    
    private fun convertToPayload(response: Response): ResponsePayload {
        val headers = response.headers.toMultimap()
            .map { (name, values) -> name to values.joinToString(", ") }
            .toMap()
        
        val body = response.body?.bytes()?.let {
            Base64.getEncoder().encodeToString(it)
        }
        
        return ResponsePayload(
            statusCode = response.code,
            headers = headers,
            body = body
        )
    }
}
