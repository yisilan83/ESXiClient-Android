package dev.esxiclient.app.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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

    val service: EsxiApiService = object : EsxiApiService {
        override suspend fun executeSoap(url: String, soapXml: String): okhttp3.Response {
            val finalUrl = if (url.startsWith("https://")) "$url/sdk" else "https://$url/sdk"
            val request = Request.Builder()
                .url(finalUrl)
                .post(soapXml.toRequestBody("text/xml".toMediaType()))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"urn:vim25/8.0\"")
                .build()

            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                call.enqueue(object : Callback {
                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        continuation.resume(response)
                    }
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }
                })
                continuation.invokeOnCancellation {
                    call.cancel()
                }
            }
        }
    }
}