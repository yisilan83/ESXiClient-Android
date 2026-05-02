package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * ESXi Host Client internal JSON API repository.
 *
 * Uses the ESXi Host Client's own JSON endpoints (same as the
 * official ESXi Web UI at /ui/). These endpoints are served by
 * hostd and are NOT subject to the SOAP System.Read RBAC
 * restriction, because they use the same session cookie as the
 * Web UI itself.
 *
 * Endpoints (all relative to https://host/ui/):
 *   GET  /ui/host      → host summary + hardware
 *   GET  /ui/vms       → VM list with power state
 *   POST /ui/login     → session cookie (alternative to SOAP Login)
 */
class HostClientRepository(
    private val host: String,
    private val username: String,
    private val password: String
) : EsxiRepository {

    override val priority: Int = 30  // highest priority
    override val protocolName: String = "HostUI"

    private val baseUrl: String
        get() {
            val h = host.trimEnd('/')
            return if (h.startsWith("https://")) h else "https://$h"
        }

    // ── Cookie & CSRF ──────────────────────────────────────────────

    private var sessionCookie: String? = null
    private var csrfToken: String? = null

    private suspend fun ensureSession() {
        if (sessionCookie != null) return

        // Login via SOAP (we know this works)
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

        // Extract session key from login response: <key>uuid</key>
        val key = Regex("""<key>([^<]+)</key>""").find(body)?.groupValues?.get(1) ?: ""
        if (key.isNotBlank()) {
            sessionCookie = "vmware_client=VMware; vmware_soap_session=$key"
            Log.d("HOSTUI", "Got session: ${key.take(12)}...")
        } else {
            Log.e("HOSTUI", "Login failed: $body")
        }
    }

    private suspend fun fetchJson(path: String): String {
        ensureSession()
        val url = "$baseUrl$path"
        Log.d("HOSTUI", "GET $url")

        return withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cookie", sessionCookie ?: "")
            conn.setRequestProperty("Accept", "application/json")
            conn.hostnameVerifier = org.apache.http.conn.ssl.NoopHostnameVerifier()
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.readText() ?: ""
                Log.d("HOSTUI", "HTTP $code: ${text.take(300)}")
                text
            } finally {
                conn.disconnect()
            }
        }
    }

    // ── Host Info ──────────────────────────────────────────────────

    override suspend fun getHostInfo(): HostInfo {
        try {
            val json = fetchJson("/ui/host")
            if (json.isBlank()) return emptyHost()

            // Parse simple JSON manually (avoid adding Gson/Moshi dependency)
            val name    = jsonStr(json, "name") ?: jsonStr(json, "hostname") ?: host
            val version = jsonStr(json, "fullName") ?: jsonStr(json, "version") ?: "Unknown"
            val cpuPct  = jsonInt(json, "cpuUsage") ?: jsonInt(json, "overallCpuUsage") ?: 0
            val memPct  = jsonInt(json, "memoryUsage") ?: jsonInt(json, "overallMemoryUsage") ?: 0
            val memGB   = jsonLong(json, "totalMemoryGB") ?: jsonLong(json, "memorySize")?.div(1024*1024*1024) ?: 0L
            val uptime  = jsonLong(json, "uptimeSeconds") ?: jsonLong(json, "uptime") ?: 0L

            Log.d("HOSTUI", "Host: $name v$version CPU=$cpuPct% MEM=$memPct% UP=${uptime}s")

            return HostInfo(
                hostname = name, hostAddress = host,
                version = version,
                cpuUsagePercent = cpuPct.coerceIn(0, 100),
                memoryUsagePercent = memPct.coerceIn(0, 100),
                totalMemoryGB = memGB.toInt(),
                uptimeSeconds = uptime,
                runningVmCount = 0, totalVmCount = 0,
                storageUsedGB = 0L, storageTotalGB = 0L
            )
        } catch (e: Exception) {
            Log.e("HOSTUI", "getHostInfo failed", e)
            return emptyHost()
        }
    }

    // ── VM List ────────────────────────────────────────────────────

    override suspend fun getVmList(): List<VmInfo> {
        try {
            val json = fetchJson("/ui/vms")
            if (json.isBlank()) return emptyList()

            // Try to parse JSON array of VMs
            val vms = mutableListOf<VmInfo>()
            // Simple parser: find all VM objects in JSON
            val vmPattern = Regex("""\{[^}]*"id"\s*:\s*"([^"]+)"[^}]*"name"\s*:\s*"([^"]+)"[^}]*"powerState"\s*:\s*"([^"]+)"[^}]*\}""")
            vmPattern.findAll(json).forEach { match ->
                val id    = match.groupValues[1]
                val name  = match.groupValues[2]
                val power = match.groupValues[3]
                vms.add(VmInfo(
                    id = id, name = name,
                    powerState = when (power) {
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
            Log.e("HOSTUI", "getVmList failed", e)
            return emptyList()
        }
    }

    override suspend fun getVmById(vmId: String) = getVmList().find { it.id == vmId }
    override suspend fun toggleVmPower(vmId: String) = false

    // ── Simple JSON helpers (no external library) ──────────────────

    private fun jsonStr(json: String, key: String): String? {
        return Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
    }
    private fun jsonInt(json: String, key: String): Int? {
        return Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()
    }
    private fun jsonLong(json: String, key: String): Long? {
        return Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun emptyHost() = HostInfo(host, host, version = "Unknown",
        cpuUsagePercent = 0, memoryUsagePercent = 0, totalMemoryGB = 0,
        uptimeSeconds = 0, runningVmCount = 0, totalVmCount = 0,
        storageUsedGB = 0, storageTotalGB = 0)
}
