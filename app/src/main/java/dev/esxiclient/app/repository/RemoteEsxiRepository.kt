package dev.esxiclient.app.repository

import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient
import dev.esxiclient.app.data.local.SessionManager

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {

    private suspend fun callSoap(soapXml: String): String {
        val response = RetrofitClient.service.executeSoap(host, soapXml)
        val body = response.body?.string() ?: ""
        response.close()
        return body
    }

    override suspend fun getHostInfo(): HostInfo {
        var fullVersion = "Unknown"
        try {
            val soap = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrieveServiceContent>
      <vim:_this type="ServiceInstance">ServiceInstance</vim:_this>
    </vim:RetrieveServiceContent>
  </soapenv:Body>
</soapenv:Envelope>"""
            val responseText = callSoap(soap)
            if ("<fullName>" in responseText) {
                fullVersion = responseText.substringAfter("<fullName>").substringBefore("</fullName>")
            }
        } catch (_: Exception) {}

        // 正则化版本号：从 "VMware ESXi 8.0.0 build-21203435" 提取为 "8.0.0"
        val shortVersion = normalizeVersion(fullVersion)

        return HostInfo(
            hostname = host,
            hostAddress = host,
            version = shortVersion,
            cpuUsagePercent = 0,
            memoryUsagePercent = 0,
            totalMemoryGB = 0,
            uptimeSeconds = 0,
            runningVmCount = 0,
            totalVmCount = 0,
            storageUsedGB = 0,
            storageTotalGB = 0
        )
    }

    private fun normalizeVersion(raw: String): String {
        val regex = Regex("""(\d+\.\d+(?:\.\d+)?)""")
        return regex.find(raw)?.value ?: raw
    }

    override suspend fun getVmList(): List<VmInfo> {
        return emptyList()
    }

    override suspend fun getVmById(vmId: String): VmInfo? {
        return null
    }

    override suspend fun toggleVmPower(vmId: String): Boolean {
        return false
    }
}