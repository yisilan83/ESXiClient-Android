package dev.esxiclient.app.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
        .build()

    val service: EsxiApiService = object : EsxiApiService {
        override suspend fun executeSoap(url: String, soapXml: String): okhttp3.Response {
            val formattedUrl = if (!url.startsWith("https://")) "https://$url" else url
            val fullUrl = "$formattedUrl/sdk"

            val request = Request.Builder()
                .url(fullUrl)
                .post(soapXml.toRequestBody("text/xml".toMediaType()))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"urn:vim25/8.0\"")
                .build()

            return client.newCall(request).execute()
        }
    }
}