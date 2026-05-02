package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * ESXi Host Client internal JSON API repository.
 *
 * Uses the ESXi Host Client's own JSON endpoints (same as the
 * official ESXi Web UI at /ui/). These endpoints are served by
 * hostd and are NOT subject to the SOAP System.Read RBAC
 * restriction — they use the same session cookie mechanism as
 * the official Web UI.
 */
class HostClientRepository(
    private val host: String,
    private val username: String,
    private val password: String
) : EsxiRepository {

    override val priority: Int = 30   // higher than REST(20) and SOAP(10)
    override val protocolName: String = "HostUI"

    private val baseUrl: String
        get() {
            val h = host.trimEnd('/')
            return if (h.startsWith("https://")) h else "https://$h"
        }

    // Shared OkHttp with trust-all SSL (same as RetrofitClient)
    private val httpClient: OkHttpClient by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ssl = SSLContext.getInstance("SSL").apply { init(null, trustAll, SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .build()
    }

    // ── Session & CSRF ─────────────────────────────────────────────

    private var sessionCookie: String? = null

    private suspend fun ensureSession() {
        if (sessionCookie != null) return

        val soapBody = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:Login>
      <vim:_this type="SessionManager">ha-sessionmgr</vim:_this>
      <vim:userName>${username.replace("&", "&amp;").replace("<", "&lt;")}</vim:userName>
      <vim:password>${password.replace("&", "&amp;").replace("<", "&lt;")}</vim:password>
      <vim:locale>zh-CN</vim:locale>
    </vim:Login>
  </soapenv:Body>
</soapenv:Envelope>"""

        val r = RetrofitClient.service.executeSoap(host, soapBody, null)
        val body = r.body?.string() ?: ""
        r.close()

        val key = Regex("""<key>([^<]+)</key>""").find(body)?.groupValues?.get(1) ?: ""
        if (key.isNotBlank()) {
            sessionCookie = "vmware_client=VMware; vmware_soap_session=$key"
            Log.d("HOSTUI", "Session OK: ${key.take(12)}...")
        } else {
            Log.e("HOSTUI", "Login failed: $body")
        }
    }

    private suspend fun fetchJson(path: String): String {
        ensureSession()
        val url = "$baseUrl$path"
        Log.d("HOSTUI", "GET $url")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Cookie", sessionCookie ?: "")
            .header("Accept", "application/json, text/html")
            .build()

        val resp = httpClient.newCall(request).execute()
        val text = resp.body?.string() ?: ""
        resp.close()
        Log.d("HOSTUI", "HTTP ${resp.code}: ${text.take(400)}")
        return text
    }

    // ── Host Info ──────────────────────────────────────────────────

    override suspend fun getHostInfo(): HostInfo {
        try {
            val json = fetchJson("/ui/host")
            if (json.isBlank()) return emptyHost()

            // Parse JSON without a library
            val name    = jsonStr(json, "name") ?: host
            val version = jsonStr(json, "fullName") ?: "Unknown"
            val cpuPct  = jsonInt(json, "cpuUsagePercent") ?: jsonInt(json, "cpuUsage") ?: 0
            val memPct  = jsonInt(json, "memoryUsagePercent") ?: jsonInt(json, "memoryUsage") ?: 0
            val memGB   = jsonLong(json, "totalMemoryGB") ?: jsonLong(json, "memorySize")?.div(1024 * 1024 * 1024) ?: 0L
            val uptime  = jsonLong(json, "uptimeSeconds") ?: jsonLong(json, "uptime") ?: 0L

            Log.d("HOSTUI", "→ $name v$version CPU=$cpuPct% MEM=$memPct% UP=${uptime}s MEMGB=$memGB")

            return HostInfo(
                hostname = name, hostAddress = host,
                version = version,
                cpuUsagePercent = cpuPct.coerceIn(0, 100),
                memoryUsagePercent = memPct.coerceIn(0, 100),
                totalMemoryGB = memGB.coerceToInt(),
                uptimeSeconds = uptime,
                runningVmCount = 0, totalVmCount = 0,
                storageUsedGB = 0L, storageTotalGB = 0L
            )
        } catch (e: Exception) {
            Log.e("HOSTUI", "getHostInfo error", e)
            return emptyHost()
        }
    }

    // ── VM List ────────────────────────────────────────────────────

    override suspend fun getVmList(): List<VmInfo> {
        try {
            val json = fetchJson("/ui/vms")
            if (json.isBlank()) return emptyList()

            val vms = mutableListOf<VmInfo>()
            // Match VM objects: {"id":"N","name":"...","powerState":"..."}
            val re = Regex(""""id"\s*:\s*"(\d+)","name"\s*:\s*"([^"]+)","powerState"\s*:\s*"([^"]+)""")
            re.findAll(json).forEach { m ->
                vms.add(VmInfo(
                    id = m.groupValues[1], name = m.groupValues[2],
                    powerState = when (m.groupValues[3]) {
                        "poweredOn" -> PowerState.POWERED_ON
                        "suspended" -> PowerState.SUSPENDED
                        else -> PowerState.POWERED_OFF
                    },
                    cpuCount = 1, memoryMiB = 0L,
                    cpuUsagePercent = 0, memoryUsedMiB = 0L,
                    guestOs = "", ipAddress = null
                ))
            }
            Log.d("HOSTUI", "VMs: ${vms.size}")
            return vms
        } catch (e: Exception) {
            Log.e("HOSTUI", "getVmList error", e)
            return emptyList()
        }
    }

    override suspend fun getVmById(vmId: String) = getVmList().find { it.id == vmId }
    override suspend fun toggleVmPower(vmId: String) = false

    // ── Mini JSON parser ───────────────────────────────────────────

    private fun jsonStr(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    private fun jsonInt(json: String, key: String): Int? =
        Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun jsonLong(json: String, key: String): Long? =
        Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun emptyHost() = HostInfo(host, host, version = "Unknown",
        cpuUsagePercent = 0, memoryUsagePercent = 0, totalMemoryGB = 0,
        uptimeSeconds = 0, runningVmCount = 0, totalVmCount = 0,
        storageUsedGB = 0, storageTotalGB = 0)

    private fun Long.coerceToInt() = if (this > Int.MAX_VALUE) Int.MAX_VALUE else toInt()
}
