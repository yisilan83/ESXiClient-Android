package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {

    private suspend fun callSoap(soapXml: String): String {
        Log.d("R", "→ ${soapXml.take(150)}")
        val r = RetrofitClient.service.executeSoap(host, soapXml, sessionId)
        val body = r.body?.string() ?: ""
        r.close()
        Log.d("R", "← ${body.length}B ${body.take(600)}")
        return body
    }

    // === cache ===
    private var pcMoid: String = ""
    private var hostSystems: List<String> = emptyList()

    private suspend fun init(): Boolean {
        if (pcMoid.isNotBlank() && hostSystems.isNotEmpty()) return true
        try {
            val sc = callSoap(B.serviceContent())
            pcMoid = regex1("propertyCollector", sc)
            val rf = regex1("rootFolder", sc)
            Log.d("R", "pc=$pcMoid rf=$rf")
            if (pcMoid.isBlank() || rf.isBlank()) return false

            // 方案：遍历 rootFolder → Datacenter → hostFolder → HostSystem
            hostSystems = findHosts(rf)
            Log.d("R", "hosts=$hostSystems")
            return hostSystems.isNotEmpty()
        } catch (e: Exception) {
            Log.e("R", "init err ${e.message}", e)
            return false
        }
    }

    private fun regex1(tag: String, xml: String): String {
        val m = Regex("""<$tag type="\w+">([^<]+)</$tag>""").find(xml)
            ?: Regex("""<$tag[^>]*>([^<]+)</$tag>""").find(xml)
        return m?.groupValues?.get(1) ?: ""
    }

    /** 从 XML 中提取所有 MOID，匹配多种格式 */
    private fun moids(xml: String): List<String> {
        val out = mutableListOf<String>()
        out += Regex("""<value>(\w+-\d+)</value>""").findAll(xml).map { it.groupValues[1] }
        out += Regex("""<obj type="\w+">(\w+-\d+)</obj>""").findAll(xml).map { it.groupValues[1] }
        out += Regex("""type="(\w+)">(\w+-\d+)<""").findAll(xml).map { it.groupValues[2] }
        return out.distinct()
    }

    /** 递归遍历 Inventory 树找到所有 HostSystem */
    private suspend fun findHosts(rootMoid: String): List<String> {
        val hosts = mutableListOf<String>()
        val q = ArrayDeque<String>()
        q.add(rootMoid)

        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            // 查询 childEntity
            val xml = callSoap(B.folderChildren(pcMoid, cur))
            val kids = moids(xml)
            // 同时尝试从响应中直接提取 obj 元素（在 objects 块内）
            // RetrievePropertiesEx 返回结构：<objects><obj type="Folder">ha-folder-root</obj><propSet>...
            // childEntity 的值在 <val> 中以 ManagedObjectReference 数组形式出现
            // 格式如：<val xsi:type="ArrayOfManagedObjectReference"><ManagedObjectReference type="Datacenter">ha-datacenter</ManagedObjectReference>
            val extraKids = Regex("""<ManagedObjectReference type="\w+">(\w+-\d+)</ManagedObjectReference>""").findAll(xml).map { it.groupValues[1] }
            val allKids = (kids + extraKids.toList()).distinct()

            Log.d("R", "  $cur → $allKids")
            for (k in allKids) {
                when {
                    k.startsWith("host-") -> hosts.add(k)
                    k.startsWith("group-") || k.startsWith("folder-") || k.startsWith("datacenter-") -> q.add(k)
                }
            }
        }
        return hosts
    }

    // === Host Info ===

    override suspend fun getHostInfo(): HostInfo {
        if (!init()) return emptyHost("Unknown")
        val hm = hostSystems.first()
        try {
            val px = callSoap(B.hostProps(pcMoid, hm))
            Log.d("R", "HOST_PROPS:\n$px")

            val ver = callSoap(B.serviceContent()).let { tag(it, "fullName") }.ifBlank { "Unknown" }

            val cpuMhz   = prop(px, "overallCpuUsage").filter(Char::isDigit).toLongOrNull() ?: 0L
            val cpuCores = prop(px, "numCpuCores").filter(Char::isDigit).toIntOrNull() ?: 1
            val cpuHz    = prop(px, "hz").filter(Char::isDigit).toLongOrNull() ?: 1L
            val totalCpuMhz = if (cpuHz > 0) (cpuCores * cpuHz) / 1_000_000L else 0L
            val cpuUsage = if (totalCpuMhz > 0) ((cpuMhz * 100L) / totalCpuMhz).toInt() else 0

            val memMbRaw = prop(px, "overallMemoryUsage").filter(Char::isDigit).toLongOrNull() ?: 0L
            val memBytes = prop(px, "memorySize").filter(Char::isDigit).toLongOrNull() ?: 0L
            val totalMemGb = memBytes / (1024*1024*1024)
            val memUsage = if (totalMemGb > 0) ((memMbRaw * 100L) / (totalMemGb * 1024)).toInt() else 0

            val uptime = prop(px, "uptime").filter(Char::isDigit).toLongOrNull() ?: 0L
            val vmXml = prop(px, "vm")
            val vmMoids = moids(vmXml).filter { it.startsWith("vm-") }
            val totalVMs = vmMoids.size.coerceAtLeast(prop(px, "totalVmCount").filter(Char::isDigit).toIntOrNull() ?: 0)

            var su = 0L; var st = 0L
            val dsMoids = moids(prop(px, "datastore")).filter { it.startsWith("datastore-") }
            for (dm in dsMoids.take(10)) {
                try {
                    val dx = callSoap(B.dsProps(pcMoid, dm))
                    val c = prop(dx, "capacity").filter(Char::isDigit).toLongOrNull() ?: 0L
                    val f = prop(dx, "freeSpace").filter(Char::isDigit).toLongOrNull() ?: 0L
                    st += c / (1024*1024*1024); su += (c - f) / (1024*1024*1024)
                } catch (_: Exception) {}
            }

            Log.d("R", "cpu=$cpuUsage% mem=$memUsage% memGB=$totalMemGb up=${uptime}s vms=$totalVMs ds=$su/$st GB")
            return HostInfo(host, host,
                version = Regex("""(\d+\.\d+(?:\.\d+)?)""").find(ver)?.value ?: ver.ifBlank { "Unknown" },
                cpuUsagePercent = cpuUsage, memoryUsagePercent = memUsage,
                totalMemoryGB = totalMemGb.toInt(), uptimeSeconds = uptime,
                runningVmCount = 0, totalVmCount = totalVMs,
                storageUsedGB = su, storageTotalGB = st)
        } catch (e: Exception) {
            Log.e("R", "ghi err ${e.message}", e)
            return emptyHost("Unknown")
        }
    }

    override suspend fun getVmList(): List<VmInfo> {
        if (!init()) return emptyList()
        val vms = mutableListOf<VmInfo>()
        try {
            val vx = callSoap(B.hostVms(pcMoid, hostSystems.first()))
            val vmIds = moids(vx).filter { it.startsWith("vm-") }
            Log.d("R", "VMs: ${vmIds.size}")
            for (vid in vmIds.take(30)) {
                try {
                    val vp = callSoap(B.vmProps(pcMoid, vid))
                    vms.add(VmInfo(
                        id = vid, name = prop(vp, "name").ifBlank { vid },
                        powerState = when (prop(vp, "powerState")) {
                            "poweredOn" -> PowerState.POWERED_ON; "suspended" -> PowerState.SUSPENDED; else -> PowerState.POWERED_OFF
                        },
                        cpuCount = prop(vp, "numCPU").filter(Char::isDigit).toIntOrNull() ?: 1,
                        memoryMiB = prop(vp, "memoryMB").filter(Char::isDigit).toLongOrNull() ?: 0L,
                        cpuUsagePercent = (prop(vp, "overallCpuUsage").filter(Char::isDigit).toIntOrNull() ?: 0).coerceIn(0, 100),
                        memoryUsedMiB = prop(vp, "guestMemoryUsage").filter(Char::isDigit).toLongOrNull() ?: 0L,
                        guestOs = prop(vp, "guestFullName"),
                        ipAddress = prop(vp, "ipAddress").ifBlank { null }
                    ))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.e("R", "gvl err ${e.message}", e) }
        return vms
    }

    override suspend fun getVmById(vmId: String) = getVmList().find { it.id == vmId }
    override suspend fun toggleVmPower(vmId: String) = false

    // === XML helpers ===

    private fun tag(xml: String, t: String): String {
        for (p in listOf("", "vim:", "ns0:")) {
            val v = xml.substringAfter("<${p}$t>").substringBefore("</${p}$t>")
            if (v != xml) return v
        }
        return ""
    }

    private fun prop(xml: String, n: String): String =
        Regex("""<name>$n</name>\s*<val[^>]*>(.*?)</val>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim() ?: ""

    private fun emptyHost(ver: String) = HostInfo(host, host, version = ver, cpuUsagePercent = 0, memoryUsagePercent = 0, totalMemoryGB = 0, uptimeSeconds = 0, runningVmCount = 0, totalVmCount = 0, storageUsedGB = 0, storageTotalGB = 0)

    // === SOAP Builder ===

    private object B {
        fun serviceContent() = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrieveServiceContent><vim:_this type="ServiceInstance">ServiceInstance</vim:_this></vim:RetrieveServiceContent></soapenv:Body>
</soapenv:Envelope>"""

        fun folderChildren(pc: String, fid: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pc</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>Folder</vim:type>
          <vim:pathSet>childEntity</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="Folder">$fid</vim:obj>
        </vim:objectSet>
      </vim:specSet>
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""

        fun hostProps(pc: String, hm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pc</vim:_this>
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
          <vim:obj type="HostSystem">$hm</vim:obj>
        </vim:objectSet>
      </vim:specSet>
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""

        fun hostVms(pc: String, hm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pc</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>HostSystem</vim:type>
          <vim:pathSet>vm</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="HostSystem">$hm</vim:obj>
        </vim:objectSet>
      </vim:specSet>
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""

        fun vmProps(pc: String, vm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pc</vim:_this>
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
          <vim:obj type="VirtualMachine">$vm</vim:obj>
        </vim:objectSet>
      </vim:specSet>
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""

        fun dsProps(pc: String, ds: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:RetrievePropertiesEx>
      <vim:_this type="PropertyCollector">$pc</vim:_this>
      <vim:specSet>
        <vim:propSet>
          <vim:type>Datastore</vim:type>
          <vim:pathSet>summary.capacity</vim:pathSet>
          <vim:pathSet>summary.freeSpace</vim:pathSet>
        </vim:propSet>
        <vim:objectSet>
          <vim:obj type="Datastore">$ds</vim:obj>
        </vim:objectSet>
      </vim:specSet>
      <vim:options/>
    </vim:RetrievePropertiesEx>
  </soapenv:Body>
</soapenv:Envelope>"""
    }
}