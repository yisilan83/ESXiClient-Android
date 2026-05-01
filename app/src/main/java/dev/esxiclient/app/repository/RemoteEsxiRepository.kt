package dev.esxiclient.app.repository

import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {
    private val api = RetrofitClient.createService(host)

    override suspend fun getHostInfo(): HostInfo {
        var version = "Connected"
        try {
            val res = api.getHostVersion(sessionId)
            if (res.isSuccessful) {
                version = "vCenter API Supported"
            }
        } catch (e: Exception) {
            // Ignore error for unsupported endpoints
        }

        return HostInfo(
            hostname = host,
            hostAddress = host,
            version = version,
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

    override suspend fun getVmList(): List<VmInfo> {
        val response = api.getVmList(sessionId)
        if (response.isSuccessful) {
            val body = response.body()
            return body?.value?.map { dto ->
                VmInfo(
                    id = dto.vm,
                    name = dto.name,
                    powerState = when (dto.power_state) {
                        "POWERED_ON" -> PowerState.POWERED_ON
                        "SUSPENDED" -> PowerState.SUSPENDED
                        else -> PowerState.POWERED_OFF
                    },
                    cpuCount = dto.cpu_count ?: 1,
                    cpuUsagePercent = 0,
                    memoryMiB = dto.memory_size_MiB ?: 0,
                    memoryUsedMiB = 0,
                    guestOs = "Unknown",
                    ipAddress = null,
                    uptimeSeconds = 0,
                    disks = emptyList()
                )
            } ?: emptyList()
        } else {
            throw Exception("获取虚拟机失败: HTTP ${response.code()}")
        }
    }

    override suspend fun getVmById(vmId: String): VmInfo? {
        return try {
            getVmList().find { it.id == vmId }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun toggleVmPower(vmId: String): Boolean {
        // 电源操作稍后在完整网络功能中实现，目前暂不支持
        return false
    }
}