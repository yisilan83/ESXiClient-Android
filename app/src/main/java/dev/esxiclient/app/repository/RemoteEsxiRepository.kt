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
        Log.d("ESXiRepo", "→ SOAP: ${soapXml.take(200)}")
        val response = RetrofitClient.service.executeSoap(host, soapXml, sessionId)
        val body = response.body?.string() ?: ""
        response.close()
        Log.d("ESXiRepo", "← RESP (${body.length}B): ${body.take(500)}")
        return body
    }

    // ==================== 缓存 ====================

    private var _propertyCollectorMoid: String? = null
    private var _hostSystems: List<String> = emptyList()

    private suspend fun initServiceContent(): Boolean {
        if (_propertyCollectorMoid != null && _hostSystems.isNotEmpty()) return true
        Log.d("ESXiRepo", "初始化...")
        try {
            val scXml = callSoap(BUILDER.serviceContent())
            _propertyCollectorMoid = extractMoRefVal(scXml, "propertyCollector")
            Log.d("ESXiRepo", "pcMoid=$_propertyCollectorMoid")
            if (_propertyCollectorMoid.isNullOrBlank()) { Log.e("ESXiRepo", "无 pcMoid"); return false }

            // 直接查询所有 HostSystem 类型的对象（不遍历 Folder 树）
            val allHostsXml = callSoap(BUILDER.allHostSystems(_propertyCollectorMoid!!))
            _hostSystems = extractAllMoids(allHostsXml).filter { it.startsWith("host-") }
            Log.d("ESXiRepo", "HostSystem: $_hostSystems")
            return _hostSystems.isNotEmpty()
        } catch (e: Exception) {
            Log.e("ESXiRepo", "init 异常: ${e.message}", e)
            return false
        }
    }

    private fun extractMoRefVal(xml: String, typeName: String): String {
        val regex = Regex("""<$typeName type="\w+">([^<]+)</$typeName>""")
        return regex.find(xml)?.groupValues?.get(1) ?: ""
    }

    private fun extractAllMoids(xml: String): List<String> {
        // 匹配 <value>xxx-nnn</value> 或 <obj type="Xxx">xxx-nnn</obj>
        val result = mutableListOf<String>()
        result += Regex("""<value>(\w+-\d+)</value>""").findAll(xml).map { it.groupValues[1] }
        result += Regex("""<obj type="\w+">(\w+-\d+)</obj>""").findAll(xml).map { it.groupValues[1] }
        return result.distinct()
    }

    // ==================== 主机信息 ====================

    override suspend fun getHostInfo(): HostInfo {
        Log.d("ESXiRepo", "===== getHostInfo =====")
        if (!initServiceContent()) return buildHostInfo("Unknown")

        val hostMoid = _hostSystems.first()
        try {
            val propsXml = callSoap(BUILDER.hostProperties(_propertyCollectorMoid!!, hostMoid))
            Log.d("ESXiRepo", "PROPS:\n$propsXml")

            val fullVersion = callSoap(BUILDER.serviceContent()).let { extractTag(it, "fullName") }.ifBlank { "Unknown" }

            val cpuMhz    = extractProp(propsXml, "overallCpuUsage").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val cpuCores  = extractProp(propsXml, "numCpuCores").filter { it.isDigit() }.toIntOrNull() ?: 1
            val cpuHz     = extractProp(propsXml, "hz").filter { it.isDigit() }.toLongOrNull() ?: 1L
            val totalCpuMhz = if (cpuHz > 0) (cpuCores * cpuHz) / 1_000_000L else 0L
            val cpuUsage  = if (totalCpuMhz > 0) ((cpuMhz * 100L) / totalCpuMhz).toInt() else 0

            val memMbRaw  = extractProp(propsXml, "overallMemoryUsage").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val memBytes  = extractProp(propsXml, "memorySize").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val totalMemGb = memBytes / (1024*1024*1024)
            val memUsage  = if (totalMemGb > 0) ((memMbRaw * 100L) / (totalMemGb * 1024)).toInt() else 0

            val uptime    = extractProp(propsXml, "uptime").filter { it.isDigit() }.toLongOrNull() ?: 0L
            val vmMoids   = extractAllMoids(extractProp(propsXml, "vm")).filter { it.startsWith("vm-") }
            val totalVMs  = vmMoids.size.coerceAtLeast(
                extractProp(propsXml, "totalVmCount").filter { it.isDigit() }.toIntOrNull() ?: 0
            )

            var storageUsed = 0L
            var storageTotal = 0L
            val dsMoids = extractAllMoids(extractProp(propsXml, "datastore")).filter { it.startsWith("datastore-") }
            for (dsMoid in dsMoids.take(10)) {
                try {
                    val dsR = callSoap(BUILDER.datastoreProperties(_propertyCollectorMoid!!, dsMoid))
                    val cap  = extractProp(dsR, "capacity").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    val free = extractProp(dsR, "freeSpace").filter { it.isDigit() }.toLongOrNull() ?: 0L
                    storageTotal += cap / (1024*1024*1024)
                    storageUsed  += (cap - free) / (1024*1024*1024)
                } catch (_: Exception) {}
            }

            Log.d("ESXiRepo", "cpu=$cpuUsage% mem=$memUsage% memGB=$totalMemGb uptime=${uptime}s vms=$totalVMs storage=$storageUsed/$storageTotal GB")
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
            Log.d("ESXiRepo", "发现 ${vmMoids.size} VM")

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
                    vms.add(VmInfo(
                        id = vmMoid, name = name, powerState = powerState,
                        cpuCount = extractProp(vmXml, "numCPU").filter { it.isDigit() }.toIntOrNull() ?: 1,
                        memoryMiB = extractProp(vmXml, "memoryMB").filter { it.isDigit() }.toLongOrNull() ?: 0L,
                        cpuUsagePercent = (extractProp(vmXml, "overallCpuUsage").filter { it.isDigit() }.toIntOrNull() ?: 0).coerceIn(0, 100),
                        memoryUsedMiB = extractProp(vmXml, "guestMemoryUsage").filter { it.isDigit() }.toLongOrNull() ?: 0L,
                        guestOs = extractProp(vmXml, "guestFullName"),
                        ipAddress = extractProp(vmXml, "ipAddress").ifBlank { null }
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
        for (p in listOf("", "vim:", "ns0:")) {
            val v = xml.substringAfter("<${p}$tag>").substringBefore("</${p}$tag>")
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

        /** 直接查询所有 HostSystem 对象（不遍历 Folder 树，规避权限问题） */
        fun allHostSystems(pcMoid: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pcMoid</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>HostSystem</vim:type>
          <vim:pathSet>name</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="HostSystem"/>
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