package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.network.RetrofitClient

/**
 * Protocol negotiator – tries:
 *   1. HostClient JSON API (priority 30) — /ui/host bypasses System.Read
 *   2. REST API (priority 20) — /rest/vcenter/host
 *   3. SOAP (priority 10) — /sdk (works on ESXi 6.x)
 */
class EsxiConnector(private val host: String) {

    data class ConnectionResult(
        val repository: EsxiRepository,
        val usedProtocol: String
    )

    suspend fun connect(
        username: String,
        password: String,
        sessionId: String
    ): ConnectionResult {
        // 1. HostClient JSON API (uses same session, bypasses SOAP RBAC)
        if (username.isNotBlank() && password.isNotBlank()) {
            Log.i("CONN", "→ Trying HostClient (/ui/host) …")
            if (tryHostClient(username, password)) {
                Log.i("CONN", "✅ Connected via HostClient")
                return ConnectionResult(
                    HostClientRepository(host, username, password),
                    "HostUI"
                )
            }
        }

        // 2. REST API on port 443 (/rest/vcenter/host)
        if (username.isNotBlank() && password.isNotBlank()) {
            Log.i("CONN", "→ Trying REST (/rest/vcenter/host) …")
            if (tryRestApi(username, password)) {
                Log.i("CONN", "✅ Connected via REST")
                return ConnectionResult(
                    RestEsxiRepository(host, username, password),
                    "REST"
                )
            }
        }

        // 3. SOAP fallback
        if (sessionId.isNotBlank()) {
            Log.i("CONN", "→ Falling back to SOAP …")
            return ConnectionResult(
                RemoteEsxiRepository(host, sessionId),
                "SOAP"
            )
        }

        throw NoAvailableProtocolException(host)
    }

    private suspend fun tryHostClient(username: String, password: String): Boolean {
        return try {
            // Just check if /ui/ is reachable
            val repo = HostClientRepository(host, username, password)
            val info = repo.getHostInfo()
            info.hostname != "Unknown" && info.version != "Unknown"
        } catch (e: Exception) {
            Log.w("CONN", "HostClient probe: ${e.message}")
            false
        }
    }

    private suspend fun tryRestApi(username: String, password: String): Boolean {
        return try {
            val r = RetrofitClient.service.executeRest(host, "/rest/vcenter/host", username, password)
            val ok = r.isSuccessful
            r.body?.close()
            Log.d("CONN", "REST probe: HTTP ${r.code}")
            ok
        } catch (e: Exception) {
            Log.w("CONN", "REST probe: ${e.message}")
            false
        }
    }
}

class NoAvailableProtocolException(host: String) :
    Exception("No available protocol to connect to $host")
