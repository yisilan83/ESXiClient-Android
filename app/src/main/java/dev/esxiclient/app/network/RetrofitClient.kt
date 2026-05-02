package dev.esxiclient.app.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object RetrofitClient {

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    private val client = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .followRedirects(true)
        .build()

    private suspend fun executeRequest(request: Request): Response {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
            continuation.invokeOnCancellation { call.cancel() }
        }
    }

    val service: EsxiApiService = object : EsxiApiService {

        // ── SOAP ─────────────────────────────────────────────────────
        override suspend fun executeSoap(
            url: String,
            soapXml: String,
            sessionId: String?,
            apiVersion: String
        ): Response {
            // Use port 8443 — same as official ESXi Web UI
            val finalUrl = if (url.startsWith("https://")) {
                val base = url.trimEnd('/').removeSuffix("/sdk")
                "$base:8443/sdk"
            } else {
                "https://$url:8443/sdk"
            }

            val builder = Request.Builder()
                .url(finalUrl)
                .post(soapXml.toRequestBody("text/xml".toMediaType()))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "urn:vim25/$apiVersion")

            // Cookie format: vmware_client=VMware; vmware_soap_session=HEX
            // WARNING: do NOT quote the session id value!
            val cookieParts = mutableListOf<String>()
            cookieParts.add("vmware_client=VMware")
            if (!sessionId.isNullOrBlank()) {
                cookieParts.add("vmware_soap_session=$sessionId")
            }
            builder.header("Cookie", cookieParts.joinToString("; "))

            return executeRequest(builder.build())
        }

        // ── REST ─────────────────────────────────────────────────────
        override suspend fun executeRest(
            url: String,
            path: String,
            username: String,
            password: String
        ): Response {
            val base = if (url.startsWith("https://")) url.trimEnd('/') else "https://$url"
            val finalUrl = "$base:8443$path"
            val credential = Credentials.basic(username, password)
            val request = Request.Builder()
                .url(finalUrl)
                .get()
                .header("Authorization", credential)
                .header("Content-Type", "application/json")
                .build()
            return executeRequest(request)
        }
    }
}
