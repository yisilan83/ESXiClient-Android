package dev.esxiclient.app.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 这是一个构建 HTTP 客户端的单例类。
 * ESXi 主机在家庭实验室（Homelab）环境中通常使用自签名证书。
 * 因此，我们配置了一个特殊的 OkHttpClient 来信任所有的 SSL 证书。
 */
object RetrofitClient {

    // 1. 创建一个信任所有证书的 TrustManager
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    // 2. 初始化 SSL 上下文
    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    // 3. 配置日志拦截器（仅在 Debug 模式下输出网络请求详情）
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // 4. 构建 OkHttpClient
    private val okHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true } // 强制信任所有主机名
        .addInterceptor(loggingInterceptor)
        .build()

    /**
     * 根据用户填写的 IP/域名 动态创建 Retrofit 服务。
     * @param baseUrl 例如: "192.168.1.100" 或 "https://esxi.local"
     */
    fun createService(baseUrl: String): EsxiApiService {
        // 自动补全 https:// 协议头
        val formattedUrl = if (!baseUrl.startsWith("http")) "https://$baseUrl" else baseUrl
        // Retrofit 要求 baseUrl 必须以 '/' 结尾
        val urlWithSlash = if (!formattedUrl.endsWith("/")) "$formattedUrl/" else formattedUrl

        return Retrofit.Builder()
            .baseUrl(urlWithSlash)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create()) // 支持 JSON 序列化
            .build()
            .create(EsxiApiService::class.java)
    }
}