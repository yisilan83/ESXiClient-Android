package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.network.RetrofitClient

/**
 * Protocol negotiator – tries REST first (ESXi 8.0 bypass),
 * falls back to SOAP (ESXi 6.x), then SSH (vim-cmd).
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
        // 1. Try REST first (priority 20)
        if (tryRestApi(username, password)) {
            Log.i("CONN", "✅ Connected via REST")
            return ConnectionResult(
                RestEsxiRepository(host, username, password),
                "REST"
            )
        }

        // 2. Fall back to SOAP (priority 10)
        if (sessionId.isNotBlank()) {
            Log.i("CONN", "⚠ REST unavailable, falling back to SOAP")
            return ConnectionResult(
                RemoteEsxiRepository(host, sessionId),
                "SOAP"
            )
        }

        throw NoAvailableProtocolException(host)
    }

    private suspend fun tryRestApi(username: String, password: String): Boolean {
        return try {
            val r = RetrofitClient.service.executeRest(
                host, "/rest/vcenter/host", username, password
            )
            val ok = r.isSuccessful
            r.close()
            ok
        } catch (e: Exception) {
            Log.w("CONN", "REST probe failed: ${e.message}")
            false
        }
    }
}

class NoAvailableProtocolException(host: String) :
    Exception("No available protocol to connect to $host")
