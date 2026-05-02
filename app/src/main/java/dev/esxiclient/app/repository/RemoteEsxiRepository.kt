package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {

    // ==================== 核心 SOAP 调用（携带 sessionId） ====================

    private suspend fun callSoap(soapXml: String): String {
        Log.d("ESXiRepo", "→ SOAP 请求: ${soapXml.take(200)}")
        val response = RetrofitClient.service.executeSoap(host, soapXml, sessionId)
        val body = response.body?.string() ?: ""
        response.close()
        Log.d("ESXiRepo", "← SOAP 响应 (${body.length} 字符): ${body.take(400)}")
        return body
    }

    // ==================== ServiceContent（缓存） ====================

    private var _propertyCollectorMoid: String? = null
    private var _hostSystems: List<String> = emptyList()

    private suspend fun initServiceContent(): Boolean {
        if (_propertyCollectorMoid != null && _hostSystems.isNotEmpty()) return true
        Log.d("ESXiRepo", "初始化 ServiceContent...")
        try {
            val scXml = callSoap(BUILDER.serviceContent())
            _propertyCollectorMoid = extractMoRefVal(scXml, "propertyCollector")
            val rootFolder = extractMoRefVal(scXml, "rootFolder")
            Log.d("ESXiRepo", "pcMoid=$_propertyCollectorMoid  rootFolder=$rootFolder")

            if (_propertyCollectorMoid.isNullOrBlank() || rootFolder.isBlank()) {
                Log.e("ESXiRepo", "无法解析 ServiceContent 关键引用")
                return false
            }

            _hostSystems = traverseToFindHostSystems(rootFolder)
            Log.d("ESXiRepo", "发现 ${_hostSystems.size} 个 HostSystem: $_hostSystems")
            return _hostSystems.isNotEmpty()
        } catch (e: Exception) {
            Log.e("ESXiRepo", "initServiceContent 异常: ${e.message}", e)
            return false
        }
    }

    private fun extractMoRefVal(xml: String, typeName: String): String {
        val regex = Regex("""<$typeName type="\w+">([^<]+)</$typeName>""")
        val m = regex.find(xml) ?: Regex("""<$typeName[^>]*>([^<]+)</$typeName>""").find(xml)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun extractAllMoids(xml: String): List<String> {
        return Regex("""<value>(\w+-\d+)</value>""").findAll(xml).map { it.groupValues[1] }.toList().distinct()
    }

    private suspend fun traverseToFindHostSystems(folderMoid: String): List<String> {
        val hosts = mutableListOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(folderMoid)

        while (queue.isNotEmpty()) {
            val currentMoid = queue.removeFirst()
            try {
                val childXml = callSoap(BUILDER.folderChildEntity(_propertyCollectorMoid!!, currentMoid))
                val allMoids = extractAllMoids(childXml)
                Log.d("ESXiRepo", "  folder $currentMoid → childEntities: $allMoids")
                for (moid in allMoids) {
                    when {
                        moid.startsWith("host-") -> hosts.add(moid)
                        moid.startsWith("group-") || moid.startsWith("folder-") || moid.startsWith("datacenter-") ->
                            queue.add(moid)
                    }
                }
            } catch (e: Exception) {
                Log.w("ESXiRepo", "遍历 folder $currentMoid 失败: ${e.message}")
            }
        }
        return hosts
    }

    // ==================== 主机信息 ====================

    override suspend fun getHostInfo(): HostInfo {
        Log.d("ESXiRepo", "===== getHostInfo =====")
        if (!initServiceContent()) return buildHostInfo("Unknown")

        val hostMoid = _hostSystems.first()
        Log.d("ESXiRepo", "查询 HostSystem: $hostMoid")

        try {
            val propsXml = callSoap(BUILDER.hostProperties(_propertyCollectorMoid!!, hostMoid))
            Log.d("ESXiRepo", "=== 主机属性 XML ===\n$propsXml\n=== END ===")

            val fullVersion = callSoap(BUILDER.serviceContent()).let { extractTag(it, "fullName") }.ifBlank { "Unknown" }

            val cpuMhz    = extractProp(propsXml, "overallCpuUsage").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val cpuCores  = extractProp(propsXml, "numCpuCores").filter { it.isDigit() }.toIntOrNull() ?: 1
            val cpuHz     = extractProp(propsXml, "hz").filter { it.isDigit() }.toLongOrNull() ?: 1L
            val totalCpuMhz = if (cpuHz > 0 && cpuCores > 0) (cpuCores * cpuHz) / 1_000_000L else 0L
            val cpuUsage  = if (totalCpuMhz > 0) ((cpuMhz * 100L) / totalCpuMhz).toInt() else 0

            val memMbRaw  = extractProp(propsXml, "overallMemoryUsage").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val memBytes  = extractProp(propsXml, "memorySize").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val totalMemGb = memBytes / (1024 * 1024 * 1024)
            val memUsage  = if (totalMemGb > 0) ((memMbRaw * 100L) / (totalMemGb * 1024)).toInt() else 0

            val uptime    = extractProp(propsXml, "uptime").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val vmMoids   = extractAllMoids(extractProp(propsXml, "vm")).filter { it.startsWith("vm-") }
            val totalVMs  = vmMoids.size.coerceAtLeast(
                extractProp(propsXml, "totalVmCount").filter { it.isDigit() }.toIntOrNull() ?: 0
            )

            // 存储
            var storageUsed = 0L
            var storageTotal = 0L
            val dsXml = extractProp(propsXml, "datastore")
            val dsMoids = extractAllMoids(dsXml).filter { it.startsWith("datastore-") }
            Log.d("ESXiRepo", "datastore MOIDs: $dsMoids")
            for (dsMoid in dsMoids.take(10)) {
                try {
                    val dsR = callSoap(BUILDER.datastoreProperties(_propertyCollectorMoid!!, dsMoid))
                    val cap  = extractProp(dsR, "capacity").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    val free = extractProp(dsR, "freeSpace").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    storageTotal += cap / (1024*1024*1024)
                    storageUsed  += (cap - free) / (1024*1024*1024)
                } catch (_: Exception) {}
            }

            Log.d("ESXiRepo", "cpu=$cpuUsage% mem=$memUsage% totalMem=${totalMemGb}GB uptime=${uptime}s vms=$totalVMs storage=$storageUsed/$storageTotal GB")
            return buildHostInfo(fullVersion, cpuUsage, memUsage, totalMemGb, uptime, 0, totalVMs, storageUsed, storageTotal)
        } catch (e: Exception) {
            Log.e("ESXiRepo", "getHostInfo 异常: ${e.message}", e)
            return buildHostInfo("Unknown")
        }
    }

    // ==================== VM 列表 ====================

    override suspend fun getVmList(): List<VmInfo> {
        Log.d("ESXiRepo", "===== getVmList =====")
        if (!initServiceContent()) return emptyList()
        val vms = mutableListOf<VmInfo>()

        try {
            val hostVmXml = callSoap(BUILDER.hostVmList(_propertyCollectorMoid!!, _hostSystems.first()))
            val vmMoids = extractAllMoids(hostVmXml).filter { it.startsWith("vm-") }
            Log.d("ESXiRepo", "发现 ${vmMoids.size} 个 VM")

            for (vmMoid in vmMoids.take(30)) {
                try {
                    val vmXml = callSoap(BUILDER.vmProperties(_propertyCollectorMoid!!, vmMoid))
                    val name  = extractProp(vmXml, "name").ifBlank { vmMoid }
                    val ps    = extractProp(vmXml, "powerState")
                    val powerState = when (ps) {
                        "poweredOn" -> PowerState.POWERED_ON
                        "suspended" -> PowerState.SUSPENDED
                        else -> PowerState.POWERED_OFF
                    }
                    val cpuCount = extractProp(vmXml, "numCPU").filter { it.isDigit() }.toIntOrNull() ?: 1
                    val memMb    = extractProp(vmXml, "memoryMB").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    val cpuUsed  = extractProp(vmXml, "overallCpuUsage").filter { it.isDigit() }.toIntOrNull() ?: 0
                    val memUsed  = extractProp(vmXml, "guestMemoryUsage").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    val guestOs  = extractProp(vmXml, "guestFullName")
                    val ip       = extractProp(vmXml, "ipAddress")

                    vms.add(VmInfo(
                        id = vmMoid, name = name, powerState = powerState,
                        cpuCount = cpuCount, cpuUsagePercent = cpuUsed.coerceIn(0, 100),
                        memoryMiB = memMb, memoryUsedMiB = memUsed,
                        guestOs = guestOs, ipAddress = ip.ifBlank { null }
                    ))
                } catch (e: Exception) {
                    Log.w("ESXiRepo", "跳过 VM $vmMoid: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("ESXiRepo", "getVmList 异常: ${e.message}", e)
        }
        return vms
    }

    override suspend fun getVmById(vmId: String): VmInfo? = getVmList().find { it.id == vmId }
    override suspend fun toggleVmPower(vmId: String): Boolean = false

    // ==================== XML 解析 ====================

    private fun extractTag(xml: String, tag: String): String {
        for (prefix in listOf("", "vim:", "ns0:")) {
            val v = xml.substringAfter("<${prefix}$tag>").substringBefore("</${prefix}$tag>")
            if (v != xml) return v
        }
        return ""
    }

    private fun extractProp(xml: String, name: String): String {
        val regex = Regex("""<name>$name</name>\s*<val[^>]*>(.*?)</val>""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun buildHostInfo(
        fullVersion: String, cpu: Int = 0, mem: Int = 0, totalMem: Long = 0,
        uptime: Long = 0, runningVMs: Int = 0, totalVMs: Int = 0,
        storageUsed: Long = 0, storageTotal: Long = 0
    ) = HostInfo(
        hostname = host, hostAddress = host,
        version = Regex("""(\d+\.\d+(?:\.\d+)?)""").find(fullVersion)?.value ?: fullVersion.ifBlank { "Unknown" },
        cpuUsagePercent = cpu, memoryUsagePercent = mem,
        totalMemoryGB = totalMem.toInt(), uptimeSeconds = uptime,
        runningVmCount = runningVMs, totalVmCount = totalVMs,
        storageUsedGB = storageUsed, storageTotalGB = storageTotal
    )

    // ==================== SOAP XML Builder ====================

    private object BUILDER {
        fun serviceContent() = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrieveServiceContent>
      <vim:_this type="ServiceInstance">ServiceInstance</vim:_this>
    </vim:RetrieveServiceContent>
  </soapenv:Body>
</soapenv:Envelope>"""

        fun folderChildEntity(pcMoid: String, folderMoid: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pcMoid</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>Folder</vim:type>
          <vim:pathSet>childEntity</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="Folder">$folderMoid</vim:obj>
        </vim:objectSet>
      </vim:specSet>
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""

        fun hostProperties(pcMoid: String, hostMoid: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pcMoid</vim:_this>
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
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""

        fun hostVmList(pcMoid: String, hostMoid: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pcMoid</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>HostSystem</vim:type>
          <vim:pathSet>vm</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="HostSystem">$hostMoid</vim:obj>
        </vim:objectSet>
      </vim:specSet>
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""

        fun vmProperties(pcMoid: String, vmMoid: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pcMoid</vim:_this>
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
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""

        fun datastoreProperties(pcMoid: String, dsMoid: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pcMoid</vim:_this>
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
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""
    }
}