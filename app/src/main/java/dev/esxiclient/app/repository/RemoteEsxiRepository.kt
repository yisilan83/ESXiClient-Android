package dev.esxiclient.app.repository

import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient
import dev.esxiclient.app.network.EsxiApiService
import dev.esxiclient.app.network.dto.*

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {
    private val api: EsxiApiService = RetrofitClient.createService(host)

    override suspend fun getHostInfo(): HostInfo {
        var version = "Connected"
        try {
            val envelope = SoapEnvelope(body = SoapBody(retrieveServiceContent = RetrieveServiceContentRequest()))
            val res = api.soapRequest(envelope)
            if (res.isSuccessful) {
                val about = res.body()?.body?.retrieveServiceContentResponse?.returnVal?.about
                version = about?.fullName ?: "vSphere SOAP API"
            }
        } catch (e: Exception) {
            version = "Connected (SOAP)"
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
        try {
            // 1. 获取 ServiceContent 以拿到 RootFolder 和 PropertyCollector
            val scEnvelope = SoapEnvelope(body = SoapBody(retrieveServiceContent = RetrieveServiceContentRequest()))
            val scRes = api.soapRequest(scEnvelope)
            val sc = scRes.body()?.body?.retrieveServiceContentResponse?.returnVal ?: throw Exception("无法获取 ServiceContent")
            
            // 2. 构建遍历和过滤规则 (简化版，仅获取名称和状态)
            val spec = PropertyFilterSpec(
                propSet = PropertySpec(),
                objectSet = ObjectSpec(
                    obj = sc.rootFolder,
                    selectSet = listOf(
                        TraversalSpec(
                            name = "folderTraversal",
                            type = "Folder",
                            path = "childEntity",
                            skip = false,
                            selectSet = listOf(SelectionSpec("folderTraversal"), SelectionSpec("datacenterVmTraversal"))
                        ),
                        TraversalSpec(
                            name = "datacenterVmTraversal",
                            type = "Datacenter",
                            path = "vmFolder",
                            skip = false,
                            selectSet = listOf(SelectionSpec("folderTraversal"))
                        )
                    )
                )
            )
            
            val propEnvelope = SoapEnvelope(body = SoapBody(retrievePropertiesEx = RetrievePropertiesExRequest(
                _this = sc.propertyCollector,
                specSet = spec
            )))
            
            val response = api.soapRequest(propEnvelope)
            if (response.isSuccessful) {
                val objects = response.body()?.body?.retrievePropertiesExResponse?.returnVal?.objects
                return objects?.map { obj ->
                    val props = obj.propSet?.associate { it.name to it.value.toString() } ?: emptyMap()
                    VmInfo(
                        id = obj.obj?.value ?: "unknown",
                        name = props["name"] ?: "Unknown VM",
                        powerState = when (props["summary.runtime.powerState"]) {
                            "poweredOn" -> PowerState.POWERED_ON
                            "suspended" -> PowerState.SUSPENDED
                            else -> PowerState.POWERED_OFF
                        },
                        cpuCount = 1,
                        cpuUsagePercent = 0,
                        memoryMiB = 0,
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
        } catch (e: Exception) {
            throw Exception("SOAP 请求失败: ${e.message}")
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
        return false
    }
}
