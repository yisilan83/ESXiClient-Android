package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {

    // ==================== 核心 SOAP 调用 ====================

    private suspend fun callSoap(soapXml: String): String {
        Log.d("ESXiRepo", "→ SOAP 请求: ${soapXml.take(300)}")
        val response = RetrofitClient.service.executeSoap(host, soapXml)
        val body = response.body?.string() ?: ""
        response.close()
        Log.d("ESXiRepo", "← SOAP 响应 (${body.length} 字符): ${body.take(500)}")
        return body
    }

    // ==================== ServiceContent & 路由信息（缓存） ====================

    private var _propertyCollectorMoid: String? = null
    private var _hostSystems: List<String> = emptyList()

    /** 初始化 ServiceContent，缓存 propertyCollector MOID 并发现所有 HostSystem */
    private suspend fun initServiceContent(): Boolean {
        if (_propertyCollectorMoid != null && _hostSystems.isNotEmpty()) return true
        Log.d("ESXiRepo", "初始化 ServiceContent...")
        try {
            val scXml = callSoap(BUILDER.serviceContent())
            // 解析 propertyCollector MOID
            _propertyCollectorMoid = extractMoRefValue(scXml, "propertyCollector")
            Log.d("ESXiRepo", "propertyCollector MOID = $_propertyCollectorMoid")

            // 解析 rootFolder MOID
            val rootFolder = extractMoRefValue(scXml, "rootFolder")
            Log.d("ESXiRepo", "rootFolder MOID = $rootFolder")

            if (_propertyCollectorMoid.isNullOrBlank() || rootFolder.isBlank()) {
                Log.e("ESXiRepo", "无法解析 ServiceContent 中的关键引用")
                return false
            }

            // 遍历 rootFolder → childEntity → 找到所有 HostSystem
            _hostSystems = traverseToFindHostSystems(rootFolder, scXml)
            Log.d("ESXiRepo", "发现 ${_hostSystems.size} 个 HostSystem: $_hostSystems")
            return _hostSystems.isNotEmpty()
        } catch (e: Exception) {
            Log.e("ESXiRepo", "initServiceContent 失败: ${e.message}", e)
            return false
        }
    }

    private fun extractMoRefValue(xml: String, type: String): String {
        // 匹配 pattern: <type>propertyCollector</type><value>ha-property-collector</value>
        // 或 namespace 版本
        val patterns = listOf(
            Regex("""<$type[^>]*>(\w[^<]*)</$type>"""),
            Regex("""<vim:$type[^>]*>(\w[^<]*)</vim:$type>"""),
            Regex("""<ns0:$type[^>]*>(\w[^<]*)</ns0:$type>""")
        )
        for (pat in patterns) {
            val m = pat.find(xml)
            if (m != null) {
                val typeName = m.groupValues[1].trim()
                // 找到对应的 value
                val valueRegex = Regex("""<value[^>]*>$typeName</value>""")
                val vm = valueRegex.find(xml)
                return vm?.groupValues?.get(1) ?: typeName
            }
        }
        // 回退：直接找 type 和 value
        val typeMatch = Regex("""<$type[^>]*>(\w[^<]*)</$type>""").find(xml)
        if (typeMatch != null) {
            val typeName = typeMatch.groupValues[1].trim()
            val valueMatch = Regex("""<value[^>]*>$typeName</value>""").find(xml)
            return valueMatch?.groupValues?.get(1) ?: typeName
        }
        return ""
    }

    /** 从 XML 中提取特定类型的 MOID 值 */
    private fun extractMoidOfType(xml: String, targetType: String): String? {
        // match: <type>Folder</type><value>group-d1</value> or <type>HostSystem</type><value>host-xxx</value>
        val regex = Regex("""<type>$targetType</type>\s*<value>(\w+-\d+)</value>""")
        return regex.find(xml)?.groupValues?.get(1)
    }

    /** 提取所有指定类型的 MOID */
    private fun extractAllMoidsOfType(xml: String, targetType: String): List<String> {
        val regex = Regex("""<type>$targetType</type>\s*<value>(\w+-\d+)</value>""")
        return regex.findAll(xml).map { it.groupValues[1] }.toList()
    }

    /** 遍历 Folder 树找到所有 HostSystem */
    private suspend fun traverseToFindHostSystems(folderMoid: String, scXml: String): List<String> {
        val hosts = mutableListOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(folderMoid)

        while (queue.isNotEmpty()) {
            val currentMoid = queue.removeFirst()
            try {
                val childXml = callSoap(BUILDER.folderChildEntity(currentMoid, _propertyCollectorMoid!!))
                // 提取 childEntity 中所有 ManagedObjectReference
                val allMoids = extractAllMoRefs(childXml)
                for (moid in allMoids) {
                    if (moid.startsWith("host-")) {
                        hosts.add(moid)
                    } else if (moid.startsWith("group-") || moid.startsWith("folder-") || moid.startsWith("datacenter-")) {
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
        Log.d("ESXiRepo", "===== getHostInfo 开始 =====")
        if (!initServiceContent()) {
            Log.e("ESXiRepo", "initServiceContent 失败")
            return buildHostInfo("Unknown")
        }

        val hostMoid = _hostSystems.first()
        Log.d("ESXiRepo", "使用 HostSystem: $hostMoid")

        try {
            val propsXml = callSoap(BUILDER.hostProperties(_propertyCollectorMoid!!, hostMoid))
            Log.d("ESXiRepo", "=== 主机属性原始 XML ===\n$propsXml\n=== END ===")

            // 解析各属性
            val cpuMhz      = extractPropVal(propsXml, "summary.quickStats.overallCpuUsage").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val cpuCores    = extractPropVal(propsXml, "hardware.cpuInfo.numCpuCores").filter { it.isDigit() }.toIntOrNull() ?: 1
            val cpuHz       = extractPropVal(propsXml, "hardware.cpuInfo.hz").filter { it.isDigit() }.toLongOrNull() ?: 1L
            val totalCpuMhz = if (cpuHz > 0 && cpuCores > 0) (cpuCores * cpuHz) / 1_000_000L else 0L
            val cpuUsage    = if (totalCpuMhz > 0) ((cpuMhz * 100L) / totalCpuMhz).toInt() else 0

            val memMbRaw    = extractPropVal(propsXml, "summary.quickStats.overallMemoryUsage").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val memByteRaw  = extractPropVal(propsXml, "hardware.memorySize").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val totalMemGb  = memByteRaw / (1024 * 1024 * 1024)
            val memUsage    = if (totalMemGb > 0) ((memMbRaw * 100L) / (totalMemGb * 1024)).toInt() else 0

            val uptime      = extractPropVal(propsXml, "summary.quickStats.uptime").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val fullVersion = extractPropVal(propsXml, "about.fullName").ifBlank {
                callSoap(BUILDER.serviceContent()).let { extractTag(it, "fullName") }
            }

            // VM 计数
            val vmMoids     = extractAllMoRefs(extractPropVal(propsXml, "vm"))
            val totalVMs    = vmMoids.size.coerceAtLeast(
                extractPropVal(propsXml, "summary.totalVmCount").filter { it.isDigit() }.toIntOrNull() ?: 0
            )

            // Datastore 存储统计
            var storageUsed = 0L
            var storageTotal = 0L
            val dsMoids = extractAllMoidsOfType(extractPropVal(propsXml, "summary.datastore"), "Datastore")
            if (dsMoids.isEmpty()) {
                // fallback：提取任意 ManagedObjectReference
                val allRefs = extractAllMoRefs(extractPropVal(propsXml, "summary.datastore"))
                for (moid in allRefs.filter { it.startsWith("datastore-") }.take(10)) {
                    dsMoids.toMutableList().add(moid)
                }
            }
            for (dsMoid in dsMoids.take(10)) {
                try {
                    val dsXml = callSoap(BUILDER.datastoreProperties(_propertyCollectorMoid!!, dsMoid))
                    val cap  = extractPropVal(dsXml, "summary.capacity").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    val free = extractPropVal(dsXml, "summary.freeSpace").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    storageTotal += cap / (1024*1024*1024)
                    storageUsed  += (cap - free) / (1024*1024*1024)
                } catch (_: Exception) {}
            }

            Log.d("ESXiRepo", "解析完成: cpu=$cpuUsage%, mem=$memUsage%, totalMem=$totalMemGb GB, uptime=${uptime}s, vms=$totalVMs, storage=$storageUsed/$storageTotal GB")

            return buildHostInfo(fullVersion, cpuUsage, memUsage, totalMemGb, uptime, 0, totalVMs, storageUsed, storageTotal)
        } catch (e: Exception) {
            Log.e("ESXiRepo", "getHostInfo 异常: ${e.message}", e)
            return buildHostInfo("Unknown")
        }
    }

    // ==================== 虚拟机列表 ====================

    override suspend fun getVmList(): List<VmInfo> {
        Log.d("ESXiRepo", "===== getVmList 开始 =====")
        if (!initServiceContent()) return emptyList()
        val hostMoid = _hostSystems.first()
        val vms = mutableListOf<VmInfo>()

        try {
            val hostVmXml = callSoap(BUILDER.hostVmList(_propertyCollectorMoid!!, hostMoid))
            val vmMoids = extractAllMoRefs(hostVmXml).filter { it.startsWith("vm-") }

            Log.d("ESXiRepo", "发现 ${vmMoids.size} 个 VM")

            for (vmMoid in vmMoids.take(30)) {
                try {
                    val vmXml = callSoap(BUILDER.vmProperties(_propertyCollectorMoid!!, vmMoid))
                    val name = extractPropVal(vmXml, "name").ifBlank { vmMoid }
                    val powerState = when (extractPropVal(vmXml, "runtime.powerState")) {
                        "poweredOn" -> PowerState.POWERED_ON
                        "suspended" -> PowerState.SUSPENDED
                        else -> PowerState.POWERED_OFF
                    }
                    val cpuCount = extractPropVal(vmXml, "config.hardware.numCPU").filter { it.isDigit() }.toIntOrNull() ?: 1
                    val memMb = extractPropVal(vmXml, "config.hardware.memoryMB").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    val cpuUsed = extractPropVal(vmXml, "summary.quickStats.overallCpuUsage").filter { it.isDigit() }.toIntOrNull() ?: 0
                    val memUsed = extractPropVal(vmXml, "summary.quickStats.guestMemoryUsage").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    val guestOs = extractPropVal(vmXml, "config.guestFullName")
                    val ip = extractPropVal(vmXml, "guest.ipAddress")

                    vms.add(VmInfo(
                        id = vmMoid, name = name, powerState = powerState,
                        cpuCount = cpuCount, cpuUsagePercent = cpuUsed.coerceIn(0, 100),
                        memoryMiB = memMb, memoryUsedMiB = memUsed,
                        guestOs = guestOs, ipAddress = ip.ifBlank { null }
                    ))
                    Log.d("ESXiRepo", "VM: $name ($vmMoid) - $powerState")
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

    // ==================== XML 解析辅助 ====================

    private fun extractTag(xml: String, tag: String): String {
        return xml.substringAfter("<$tag>").substringBefore("</$tag>")
    }

    /** 提取属性路径对应的值 */
    private fun extractPropVal(xml: String, targetPath: String): String {
        // 取属性路径的最后一段作为匹配 key（如 "summary.quickStats.overallCpuUsage" → "overallCpuUsage"）
        val leafName = targetPath.substringAfterLast(".")
        // 在 XML 中找到 <name>$leafName</name> 后紧跟的 <val ...>...</val>
        val regex = Regex("""<name>$leafName</name>\s*<val[^>]*>(.*?)</val>""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(xml)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractAllMoRefs(xml: String): List<String> {
        val regex = Regex("""<value>(\w+-\d+)</value>""")
        return regex.findAll(xml).map { it.groupValues[1] }.toList().distinct()
    }

    private fun buildHostInfo(
        fullVersion: String, cpu: Int = 0, mem: Int = 0, totalMem: Long = 0,
        uptime: Long = 0, runningVMs: Int = 0, totalVMs: Int = 0,
        storageUsed: Long = 0, storageTotal: Long = 0
    ) = HostInfo(
        hostname = host, hostAddress = host,
        version = Regex("""(\d+\.\d+(?:\.\d+)?)""").find(fullVersion)?.value ?: fullVersion,
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

        fun folderChildEntity(folderMoid: String, pcMoid: String) = """<?xml version="1.0" encoding="UTF-8"?>
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
          <vim:pathSet>about.fullName</vim:pathSet>
          <vim:pathSet>vm</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="HostSystem">$hostMoid</vim:obj>
        </vim:objectSet>
      </vim:specSet>
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
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""
    }
}