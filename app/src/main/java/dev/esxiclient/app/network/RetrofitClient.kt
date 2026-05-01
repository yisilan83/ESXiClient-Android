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
        .followRedirects(true)
        .build()

    val service: EsxiApiService = object : EsxiApiService {
        override suspend fun executeSoap(url: String, soapXml: String): okhttp3.Response {
            // 尝试多个 URL 变体
            val urls = listOf(
                if (url.startsWith("https://")) url else "https://$url",
                if (url.startsWith("http://")) url else "http://$url"
            )
            // 先 GET 探测一下哪个协议可通
            for (base in urls) {
                try {
                    val probe = client.newCall(Request.Builder().url(base).get().build()).execute()
                    val body = probe.body?.string() ?: ""
                    probe.close()
                    // 无论返回什么，有响应说明这个协议和端口是对的正确 URL
                    val sdk = "$base/sdk"
                    val request = Request.Builder()
                        .url(sdk)
                        .post(soapXml.toRequestBody("text/xml".toMediaType()))
                        .header("Content-Type", "text/xml; charset=utf-8")
                        .header("SOAPAction", "\"urn:vim25/8.0\"")
                        .build()
                    return client.newCall(request).execute()
                } catch (_: Exception) {
                    // 此协议不通，试下一个
                }
            }
            // 如果连 GET 都失败，用 https 发送，看返回什么
            val finalUrl = if (url.startsWith("https://")) "$url/sdk" else "https://$url/sdk"
            val request = Request.Builder()
                .url(finalUrl)
                .post(soapXml.toRequestBody("text/xml".toMediaType()))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"urn:vim25/8.0\"")
                .build()
            return client.newCall(request).execute()
        }
    }
}