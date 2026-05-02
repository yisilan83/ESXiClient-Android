package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient

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

    // ==================== 工具方法 ====================

    /** 从 SOAP XML 中提取指定标签的第一个文本内容 */
    private fun extractTag(xml: String, tag: String): String {
        return xml.substringAfter("<$tag>").substringBefore("</$tag>")
    }

    /** 从 SOAP XML 中提取命名空间标签的第一个文本内容，如 <vim:name> */
    private fun extractNsTag(xml: String, tag: String): String {
        return xml.substringAfter("<vim:$tag>").substringBefore("</vim:$tag>")
            .ifEmpty { xml.substringAfter("<ns0:$tag>").substringBefore("</ns0:$tag>") }
    }

    // ==================== 主机信息 ====================

    override suspend fun getHostInfo(): HostInfo {
        var fullVersion = "Unknown"
        var cpuUsage = 0
        var memUsage = 0
        var totalMem = 0L
        var uptime = 0L
        var runningVMs = 0
        var totalVMs = 0
        var storageUsed = 0L
        var storageTotal = 0L

        try {
            // 步骤 1: 获取 ServiceContent (rootFolder, propertyCollector, about)
            val scXml = callSoap(buildServiceContentRequest())
            val serviceContent = scXml

            // 解析版本信息
            if ("<fullName>" in scXml) {
                fullVersion = extractTag(scXml, "fullName")
            }

            // 解析 rootFolder 的 MOID
            val rootFolderId = extractNsTag(scXml, "val").ifEmpty { extractTag(scXml, "val") }
            if (rootFolderId.isBlank()) {
                Log.w("ESXiRepo", "无法解析 rootFolder MOID")
                return buildHostInfo(fullVersion)
            }

            // 步骤 2: 遍历 rootFolder → childEntity 直到找到 HostSystem
            // 先用 FindChild 或直接遍历 childEntity
            val hosts = findHostSystems(scXml)
            if (hosts.isEmpty()) {
                Log.w("ESXiRepo", "未找到 HostSystem 对象")
                return buildHostInfo(fullVersion)
            }

            val hostMoid = hosts.first()

            // 步骤 3: 查询 HostSystem 的 quickStats 和 hardware 信息
            val hostPropsXml = callSoap(
                buildHostPropertiesRequest(hostMoid)
            )

            // 解析 CPU 使用率
            // summary.quickStats.overallCpuUsage (MHz)
            val cpuMhz = extractPropVal(hostPropsXml, "summary.quickStats.overallCpuUsage").toIntOrNull() ?: 0
            val cpuCores = extractPropVal(hostPropsXml, "hardware.cpuInfo.numCpuCores").toIntOrNull() ?: 1
            val cpuHz = extractPropVal(hostPropsXml, "hardware.cpuInfo.hz").toLongOrNull() ?: 1L
            if (cpuHz > 0 && cpuCores > 0) {
                val totalCpuMhz = (cpuCores * cpuHz) / 1_000_000L
                cpuUsage = if (totalCpuMhz > 0) ((cpuMhz.toLong() * 100) / totalCpuMhz).toInt() else 0
            }

            // 解析内存使用率
            // summary.quickStats.overallMemoryUsage (MB)
            val memUsedMb = extractPropVal(hostPropsXml, "summary.quickStats.overallMemoryUsage").toLongOrNull() ?: 0L
            // hardware.memorySize (bytes)
            totalMem = (extractPropVal(hostPropsXml, "hardware.memorySize").toLongOrNull() ?: 0L) / (1024 * 1024 * 1024)
            if (totalMem > 0) {
                memUsage = ((memUsedMb * 100) / (totalMem * 1024)).toInt()
            }

            // 解析 uptime
            uptime = extractPropVal(hostPropsXml, "summary.quickStats.uptime").toLongOrNull() ?: 0L

            // 解析 VM 数量
            totalVMs = extractPropVal(hostPropsXml, "summary.totalVmCount").toIntOrNull()
                ?: extractPropVal(hostPropsXml, "vm").count { it == '<' }.coerceAtLeast(0)

            // 解析 datastore 列表 (summary.datastore 返回 MOID 数组)
            val dsXml = extractPropVal(hostPropsXml, "summary.datastore")
            val dsMoids = extractAllMoRefs(dsXml)
            if (dsMoids.isNotEmpty()) {
                for (dsMoid in dsMoids.take(10)) {
                    try {
                        val dsPropsXml = callSoap(buildDatastorePropertiesRequest(dsMoid))
                        val dsCap = extractPropVal(dsPropsXml, "summary.capacity").toLongOrNull() ?: 0L
                        val dsFree = extractPropVal(dsPropsXml, "summary.freeSpace").toLongOrNull() ?: 0L
                        storageTotal += dsCap / (1024 * 1024 * 1024)
                        storageUsed += (dsCap - dsFree) / (1024 * 1024 * 1024)
                    } catch (_: Exception) {}
                }
            }

        } catch (e: Exception) {
            Log.e("ESXiRepo", "getHostInfo 失败: ${e.message}", e)
        }

        return buildHostInfo(fullVersion, cpuUsage, memUsage, totalMem, uptime, runningVMs, totalVMs, storageUsed, storageTotal)
    }

    // ==================== 虚拟机列表 ====================

    override suspend fun getVmList(): List<VmInfo> {
        val vms = mutableListOf<VmInfo>()
        try {
            // 步骤 1: 获取 ServiceContent
            val scXml = callSoap(buildServiceContentRequest())

            // 步骤 2: 找到 HostSystem
            val hosts = findHostSystems(scXml)
            if (hosts.isEmpty()) return vms

            val hostMoid = hosts.first()

            // 步骤 3: 查询 HostSystem 的 vm 属性获取所有 VM 的 MOID
            val hostVmProps = callSoap(buildHostVmListRequest(hostMoid))
            val vmXml = extractPropVal(hostVmProps, "vm")
            val vmMoids = extractAllMoRefs(vmXml)

            // 步骤 4: 对每台 VM 查询关键属性
            for (vmMoid in vmMoids.take(30)) { // 一次最多查 30 台
                try {
                    val vmPropsXml = callSoap(buildVmPropertiesRequest(vmMoid))
                    val name = extractPropVal(vmPropsXml, "name")
                    val powerState = when (extractPropVal(vmPropsXml, "runtime.powerState")) {
                        "poweredOn" -> PowerState.POWERED_ON
                        "suspended" -> PowerState.SUSPENDED
                        else -> PowerState.POWERED_OFF
                    }
                    val cpuCount = extractPropVal(vmPropsXml, "config.hardware.numCPU").toIntOrNull() ?: 1
                    val memMb = extractPropVal(vmPropsXml, "config.hardware.memoryMB").toLongOrNull() ?: 0L
                    val cpuUsed = extractPropVal(vmPropsXml, "summary.quickStats.overallCpuUsage").toIntOrNull() ?: 0
                    val memUsed = extractPropVal(vmPropsXml, "summary.quickStats.guestMemoryUsage").toLongOrNull() ?: 0L
                    val guestOs = extractPropVal(vmPropsXml, "config.guestFullName")
                    val ip = extractPropVal(vmPropsXml, "guest.ipAddress")

                    vms.add(
                        VmInfo(
                            id = vmMoid,
                            name = name,
                            powerState = powerState,
                            cpuCount = cpuCount,
                            cpuUsagePercent = cpuUsed.coerceIn(0, 100),
                            memoryMiB = memMb,
                            memoryUsedMiB = memUsed,
                            guestOs = guestOs,
                            ipAddress = ip.ifBlank { null }
                        )
                    )
                } catch (e: Exception) {
                    Log.w("ESXiRepo", "跳过 VM $vmMoid: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("ESXiRepo", "getVmList 失败: ${e.message}", e)
        }
        return vms
    }

    override suspend fun getVmById(vmId: String): VmInfo? {
        return try {
            getVmList().find { it.id == vmId }
        } catch (_: Exception) { null }
    }

    override suspend fun toggleVmPower(vmId: String): Boolean {
        return false
    }

    // ==================== SOAP XML 构建方法 ====================

    private fun buildServiceContentRequest(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrieveServiceContent>
      <vim:_this type="ServiceInstance">ServiceInstance</vim:_this>
    </vim:RetrieveServiceContent>
  </soapenv:Body>
</soapenv:Envelope>"""
    }

    /**
     * 查询 HostSystem 的属性：quickStats + hardware + vm + datastore
     */
    private fun buildHostPropertiesRequest(hostMoid: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">propertyCollector</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>HostSystem</vim:type>
          <vim:pathSet>summary.quickStats.overallCpuUsage</vim:pathSet>
          <vim:pathSet>summary.quickStats.overallMemoryUsage</vim:pathSet>
          <vim:pathSet>summary.quickStats.uptime</vim:pathSet>
          <vim:pathSet>summary.totalVmCount</vim:pathSet>
          <vim:pathSet>summary.datastore</vim:pathSet>
          <vim:pathSet>hardware.cpuInfo.numCpuCores</vim:pathSet>
          <vim:pathSet>hardware.cpuInfo.hz</vim:pathSet>
          <vim:pathSet>hardware.memorySize</vim:pathSet>
          <vim:pathSet>vm</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="HostSystem">$hostMoid</vim:obj>
        </vim:objectSet>
      </vim:specSet>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""
    }

    /**
     * 查询 HostSystem 的 vm 属性（获取所有 VM 的 MOID 列表）
     */
    private fun buildHostVmListRequest(hostMoid: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">propertyCollector</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>HostSystem</vim:type>
          <vim:pathSet>vm</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="HostSystem">$hostMoid</vim:obj>
        </vim:objectSet>
      </vim:specSet>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""
    }

    /**
     * 查询单个 VM 的关键属性
     */
    private fun buildVmPropertiesRequest(vmMoid: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">propertyCollector</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>VirtualMachine</vim:type>
          <vim:pathSet>name</vim:pathSet>
          <vim:pathSet>runtime.powerState</vim:pathSet>
          <vim:pathSet>config.hardware.numCPU</vim:pathSet>
          <vim:pathSet>config.hardware.memoryMB</vim:pathSet>
          <vim:pathSet>config.guestFullName</vim:pathSet>
          <vim:pathSet>guest.ipAddress</vim:pathSet>
          <vim:pathSet>summary.quickStats.overallCpuUsage</vim:pathSet>
          <vim:pathSet>summary.quickStats.guestMemoryUsage</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="VirtualMachine">$vmMoid</vim:obj>
        </vim:objectSet>
      </vim:specSet>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""
    }

    /**
     * 查询 Datastore 的容量信息
     */
    private fun buildDatastorePropertiesRequest(dsMoid: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">propertyCollector</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>Datastore</vim:type>
          <vim:pathSet>summary.capacity</vim:pathSet>
          <vim:pathSet>summary.freeSpace</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="Datastore">$dsMoid</vim:obj>
        </vim:objectSet>
      </vim:specSet>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""
    }

    // ==================== XML 解析辅助方法 ====================

    /**
     * 从 RetrievePropertiesEx 响应中提取指定属性路径的值
     */
    private fun extractPropVal(xml: String, propPath: String): String {
        // 属性名在 SOAP 响应中以 <name> 标签出现
        return xml.split("<name>")
            .firstOrNull { it.endsWith(propPath) || propPath in it.substringAfterLast("<name>") }
            ?.let { segment ->
                val afterName = segment.substringAfter(propPath)
                if ("<val xsi:type=\"xsd:long\">" in afterName || "<val xsi:type=\"xsd:int\">" in afterName || 
                    "<val xsi:type=\"xsd:string\">" in afterName || "<val xsi:type=\"xsd:short\">" in afterName) {
                    afterName.substringAfter("<val").substringAfter(">").substringBefore("</val>")
                } else if ("<val>" in afterName) {
                    afterName.substringAfter("<val>").substringBefore("</val>")
                } else {
                    ""
                }
            } ?: ""
    }

    /**
     * 从 XML 中提取所有 ManagedObjectReference (type + value)
     */
    private fun extractAllMoRefs(xml: String): List<String> {
        val result = mutableListOf<String>()
        // 匹配 <val type="ManagedObjectReference">host-XXX</val> 
        // 或 <obj type="HostSystem">host-XXX</obj>
        val regexList = listOf(
            Regex("""<val[^>]*>(\w+-\d+)</val>"""),
            Regex("""<obj[^>]*>(\w+-\d+)</obj>"""),
            Regex("""type=["'](\w+)["']>(\w+-\d+)<""")
        )
        for (regex in regexList) {
            for (match in regex.findAll(xml)) {
                val moid = match.groupValues.getOrNull(2) ?: match.groupValues.getOrNull(1) ?: continue
                if (moid.matches(Regex("\\w+-\\d+"))) {
                    result.add(moid)
                }
            }
        }
        return result.distinct()
    }

    /**
     * 从 ServiceContent 响应中找到所有 HostSystem 对象的 MOID
     */
    private fun findHostSystems(scXml: String): List<String> {
        return extractAllMoRefs(scXml).filter { it.startsWith("host-") }
    }

    // ==================== 构建 HostInfo ====================

    private fun buildHostInfo(
        fullVersion: String,
        cpuUsage: Int = 0,
        memUsage: Int = 0,
        totalMemGb: Long = 0,
        uptime: Long = 0,
        runningVMs: Int = 0,
        totalVMs: Int = 0,
        storageUsed: Long = 0,
        storageTotal: Long = 0
    ): HostInfo {
        val shortVersion = normalizeVersion(fullVersion)
        return HostInfo(
            hostname = host,
            hostAddress = host,
            version = shortVersion,
            cpuUsagePercent = cpuUsage,
            memoryUsagePercent = memUsage,
            totalMemoryGB = totalMemGb.toInt(),
            uptimeSeconds = uptime,
            runningVmCount = runningVMs,
            totalVmCount = totalVMs,
            storageUsedGB = storageUsed,
            storageTotalGB = storageTotal
        )
    }

    private fun normalizeVersion(raw: String): String {
        val regex = Regex("""(\d+\.\d+(?:\.\d+)?)""")
        return regex.find(raw)?.value ?: raw
    }
}