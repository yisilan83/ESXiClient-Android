package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * ESXi REST API repository – uses Basic Auth on port 8443.
 * This bypasses the ESXi 8.0 System.Read RBAC restriction that
 * blocks the SOAP path.
 */
class RestEsxiRepository(
    private val host: String,
    private val username: String,
    private val password: String
) : EsxiRepository {

    override val priority: Int = 20
    override val protocolName: String = "REST"

    private suspend fun callRest(path: String): String {
        Log.d("REST", "→ REST GET $path")
        val r = RetrofitClient.service.executeRest(host, path, username, password)
        val body = r.body?.string() ?: ""
        r.close()
        Log.d("REST", "← REST [${r.code}] ${body.take(500)}")
        if (!r.isSuccessful) {
            throw RestException("REST $path failed: HTTP ${r.code} ${body.take(200)}")
        }
        return body
    }

    // ── Host ─────────────────────────────────────────────────────────

    override suspend fun getHostInfo(): HostInfo {
        try {
            val json = JSONObject(callRest("/rest/vcenter/host"))
            // /rest/vcenter/host returns a list; take the first host
            val hosts: JSONArray = when {
                json.has("value") -> json.getJSONArray("value")
                json.has("host")  -> json.getJSONArray("host")
                json.length() > 0 && json.names() == null -> JSONArray().apply { put(json) }
                else -> json.getJSONArray("hosts")
            }
            if (hosts.length() == 0) return emptyHost("Unknown")

            val h = hosts.getJSONObject(0)
            val hostName = h.optString("name", host)
            val powerState = h.optString("power_state", "POWERED_ON")
            val connState  = h.optString("connection_state", "CONNECTED")

            // Try to get hardware info via separate endpoint
            val hwJson = try {
                JSONObject(callRest("/rest/vcenter/host/${h.optString("host", hostName)}/hardware"))
            } catch (_: Exception) { JSONObject() }

            val cpuMhz    = hwJson.optJSONObject("cpu")?.optLong("mhz", 0L) ?: 0L
            val cpuCores  = hwJson.optJSONObject("cpu")?.optInt("cores", 0)
                ?: h.optInt("cpu_cores", 0)
            val memBytes  = hwJson.optLong("memory_size", 0L)
            val totalMemGb = if (memBytes > 0) memBytes / (1024 * 1024 * 1024) else 0L

            // Performance stats
            val cpuUsage  = h.optDouble("cpu_usage", 0.0).toInt().coerceIn(0, 100)
            val memUsage  = h.optDouble("memory_usage", 0.0).toInt().coerceIn(0, 100)
            val uptime    = h.optLong("uptime", 0L)

            // Storage
            var storageUsed: Long = 0; var storageTotal: Long = 0
            try {
                val dsJson = JSONObject(callRest("/rest/vcenter/datastore"))
                val dsArray = when {
                    dsJson.has("value") -> dsJson.getJSONArray("value")
                    else -> dsJson.optJSONArray("datastores") ?: JSONArray()
                }
                for (i in 0 until dsArray.length()) {
                    val ds = dsArray.getJSONObject(i)
                    storageTotal += ds.optLong("capacity", 0L) / (1024 * 1024 * 1024)
                    storageUsed  += (ds.optLong("capacity", 0L) - ds.optLong("free_space", 0L)) / (1024 * 1024 * 1024)
                }
            } catch (_: Exception) { /* datastore info is optional */ }

            // VM count
            var vmCount = 0
            try {
                val vmJson = JSONObject(callRest("/rest/vcenter/vm"))
                val vmArray = when {
                    vmJson.has("value") -> vmJson.getJSONArray("value")
                    else -> vmJson.optJSONArray("vms") ?: JSONArray()
                }
                vmCount = vmArray.length()
            } catch (_: Exception) { /* VM info is optional */ }

            return HostInfo(
                hostname     = hostName,
                hostAddress  = host,
                version      = h.optString("version", "Unknown"),
                cpuUsagePercent = cpuUsage,
                memoryUsagePercent = memUsage,
                totalMemoryGB    = totalMemGb.toInt(),
                uptimeSeconds    = uptime,
                runningVmCount   = vmCount,
                totalVmCount     = vmCount,
                storageUsedGB    = storageUsed,
                storageTotalGB   = storageTotal
            )
        } catch (e: RestException) { throw e }
        catch (e: Exception) {
            Log.e("REST", "getHostInfo error", e)
            return emptyHost("Unknown")
        }
    }

    // ── VM List ──────────────────────────────────────────────────────

    override suspend fun getVmList(): List<VmInfo> {
        try {
            val json = JSONObject(callRest("/rest/vcenter/vm"))
            val vmArray = when {
                json.has("value") -> json.getJSONArray("value")
                json.has("vm")    -> json.getJSONArray("vm")
                else              -> json.optJSONArray("vms") ?: JSONArray()
            }
            val vms = mutableListOf<VmInfo>()
            for (i in 0 until vmArray.length()) {
                val vm = vmArray.getJSONObject(i)
                val power = vm.optString("power_state", "POWERED_OFF")
                vms.add(VmInfo(
                    id            = vm.optString("vm", vm.optString("name", "vm-$i")),
                    name          = vm.optString("name", "Unknown"),
                    powerState    = when (power) {
                        "POWERED_ON" -> PowerState.POWERED_ON
                        "SUSPENDED"  -> PowerState.SUSPENDED
                        else         -> PowerState.POWERED_OFF
                    },
                    cpuCount      = vm.optInt("cpu_count", 1),
                    cpuUsagePercent = vm.optInt("cpu_usage", 0).coerceIn(0, 100),
                    memoryMiB     = vm.optLong("memory_size_MiB", 0L),
                    memoryUsedMiB = vm.optLong("memory_used_MiB", 0L),
                    guestOs       = vm.optString("guest_OS", ""),
                    ipAddress     = vm.optString("ip_address", "").ifBlank { null },
                    uptimeSeconds = vm.optLong("uptime", 0L)
                ))
            }
            return vms
        } catch (e: RestException) { throw e }
        catch (e: Exception) {
            Log.e("REST", "getVmList error", e)
            return emptyList()
        }
    }

    override suspend fun getVmById(vmId: String) = getVmList().find { it.id == vmId }
    override suspend fun toggleVmPower(vmId: String) = false

    // ── Helpers ───────────────────────────────────────────────────────

    private fun emptyHost(ver: String) = HostInfo(
        host, host, version = ver,
        cpuUsagePercent = 0, memoryUsagePercent = 0, totalMemoryGB = 0,
        uptimeSeconds = 0, runningVmCount = 0, totalVmCount = 0,
        storageUsedGB = 0, storageTotalGB = 0
    )
}

class RestException(message: String) : Exception(message)
