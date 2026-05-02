package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {

    private var apiVersion = "8.0"

    private suspend fun callSoap(soapXml: String, label: String = ""): String {
        Log.d("R","→$label ${soapXml.take(130)}")
        val r = RetrofitClient.service.executeSoap(host, soapXml, sessionId, apiVersion)
        val body = r.body?.string() ?: ""
        r.close()
        Log.d("R","←$label [${body.length}B]\n$body\n---")
        return body
    }

    private var pcMoid = ""
    private var dsMoid = ""

    private suspend fun init(): Boolean {
        if (pcMoid.isNotBlank()) return true
        try {
            val sc = callSoap(B.serviceContent(), "SC")
            pcMoid = rx1("propertyCollector", sc)
            apiVersion = rx1("apiVersion", sc).ifBlank { "8.0" }
            Log.d("R","pc=$pcMoid apiVer=$apiVersion")
            return pcMoid.isNotBlank()
        } catch (e: Exception) { Log.e("R","init err ${e.message}",e); return false }
    }

    /** 获取 Datastore MOID 列表，缓存 */
    private suspend fun ensureDsMoid() {
        if (dsMoid.isNotBlank()) return
        try {
            val xml = callSoap(B.hostDs(pcMoid, "ha-host"), "HOSTDS")
            // 从响应中提取所有 datastore- MOID
            dsMoid = Regex("""datastore-\d+""").find(xml)?.value ?: ""
            if (dsMoid.isBlank()) {
                // 回退：从 host configManager.datastoreSystem 查
                val xml2 = callSoap(B.dsSystem(pcMoid, "ha-host"), "DSSYS")
                dsMoid = Regex("""datastore-\d+""").find(xml2)?.value ?: ""
            }
            Log.d("R","dsMoid=$dsMoid")
        } catch (_: Exception) {}
    }

    private fun rx1(tag: String, xml: String): String {
        val m = Regex("""<$tag type="\w+">([^<]+)</$tag>""").find(xml)
            ?: Regex("""<$tag[^>]*>([^<]+)</$tag>""").find(xml)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun moids(xml: String) = Regex("""<value>(\w+-\d+)</value>""").findAll(xml).map { it.groupValues[1] }.toMutableList()
    private fun extractVmMoids(xml: String) = (moids(xml) + Regex("""<obj type="\w+">(\w+-\d+)</obj>""").findAll(xml).map { it.groupValues[1] }).filter { it.startsWith("vm-") }.distinct()

    override suspend fun getHostInfo(): HostInfo {
        if (!init()) return emptyHost("Unknown")
        try {
            val px = callSoap(B.hostProps(pcMoid, "ha-host"), "HOST")
            if (px.contains("ManagedObjectNotFound") || px.contains("has already been deleted")) {
                return emptyHost("Unknown")
            }
            val ver = callSoap(B.serviceContent(), "SC2").let { tag(it, "fullName") }.ifBlank { "Unknown" }

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
            val totalVmCount = prop(px, "totalVmCount").filter(Char::isDigit).toIntOrNull() ?: 0

            // 存储：通过独立的 datastore 属性查询
            var su = 0L; var st = 0L
            ensureDsMoid()
            if (dsMoid.isNotBlank()) {
                try {
                    val dx = callSoap(B.dsProps(pcMoid, dsMoid), "DS")
                    val c = prop(dx, "capacity").filter(Char::isDigit).toLongOrNull() ?: 0L
                    val f = prop(dx, "freeSpace").filter(Char::isDigit).toLongOrNull() ?: 0L
                    st = c / (1024*1024*1024); su = (c - f) / (1024*1024*1024)
                } catch (_: Exception) {}
            }

            Log.d("R","CPU=$cpuUsage% MEM=$memUsage% MEMGB=$totalMemGb UP=${uptime}s VMs=$totalVmCount DS=$su/$st GB")
            return buildHost(ver, cpuUsage, memUsage, totalMemGb, uptime, totalVmCount, su, st)
        } catch (e: Exception) { Log.e("R","ghi err ${e.message}",e); return emptyHost("Unknown") }
    }

    override suspend fun getVmList(): List<VmInfo> {
        if (!init()) return emptyList()
        val vms = mutableListOf<VmInfo>()
        try {
            // 先用 ContainerView 方式获取所有 VM
            val vx = callSoap(B.allVms(pcMoid), "ALLVMS")
            // 尝试从 <obj type="VirtualMachine">vm-XXX</obj> 提取
            val vmIds = extractVmMoids(vx)
            Log.d("R","ALLVMS: found ${vmIds.size} VMs from response")

            // 如果全类型查询失败，回退到 host vm 属性
            val finalIds = if (vmIds.isNotEmpty()) vmIds else {
                val vx2 = callSoap(B.hostVms(pcMoid, "ha-host"), "HOSTVM")
                extractVmMoids(vx2)
            }

            for (vid in finalIds.take(30)) {
                try {
                    val vp = callSoap(B.vmProps(pcMoid, vid), "VM$vid")
                    vms.add(VmInfo(id = vid, name = prop(vp, "name").ifBlank { vid },
                        powerState = when (prop(vp, "powerState")) {"poweredOn"->PowerState.POWERED_ON;"suspended"->PowerState.SUSPENDED;else->PowerState.POWERED_OFF},
                        cpuCount = prop(vp, "numCPU").filter(Char::isDigit).toIntOrNull()?:1,
                        memoryMiB = prop(vp, "memoryMB").filter(Char::isDigit).toLongOrNull()?:0L,
                        cpuUsagePercent = (prop(vp, "overallCpuUsage").filter(Char::isDigit).toIntOrNull()?:0).coerceIn(0,100),
                        memoryUsedMiB = prop(vp, "guestMemoryUsage").filter(Char::isDigit).toLongOrNull()?:0L,
                        guestOs = prop(vp, "guestFullName"), ipAddress = prop(vp, "ipAddress").ifBlank { null }))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.e("R","gvl err ${e.message}",e) }
        return vms
    }

    override suspend fun getVmById(vmId: String) = getVmList().find { it.id == vmId }
    override suspend fun toggleVmPower(vmId: String) = false

    private fun tag(xml: String, t: String): String { for (p in listOf("","vim:","ns0:")) { val v = xml.substringAfter("<${p}$t>").substringBefore("</${p}$t>"); if (v != xml) return v }; return "" }
    private fun prop(xml: String, n: String): String = Regex("""<name>$n</name>\s*<val[^>]*>(.*?)</val>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim() ?: ""
    private fun emptyHost(ver: String) = HostInfo(host, host, version = ver, cpuUsagePercent = 0, memoryUsagePercent = 0, totalMemoryGB = 0, uptimeSeconds = 0, runningVmCount = 0, totalVmCount = 0, storageUsedGB = 0, storageTotalGB = 0)
    private fun buildHost(ver: String, cpu: Int, mem: Int, memGb: Long, up: Long, vms: Int, su: Long, st: Long) = HostInfo(host, host, version = Regex("""(\d+\.\d+(?:\.\d+)?)""").find(ver)?.value ?: ver.ifBlank { "Unknown" }, cpuUsagePercent = cpu, memoryUsagePercent = mem, totalMemoryGB = memGb.toInt(), uptimeSeconds = up, runningVmCount = 0, totalVmCount = vms, storageUsedGB = su, storageTotalGB = st)

    private object B {
        fun serviceContent() = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrieveServiceContent><vim:_this type="ServiceInstance">ServiceInstance</vim:_this></vim:RetrieveServiceContent></soapenv:Body>
</soapenv:Envelope>"""
        // HostSystem 属性（删除了 summary.datastore，用单独查询获取）
        fun hostProps(pc: String, hm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>HostSystem</vim:type><vim:pathSet>summary.quickStats.overallCpuUsage</vim:pathSet><vim:pathSet>summary.quickStats.overallMemoryUsage</vim:pathSet><vim:pathSet>summary.quickStats.uptime</vim:pathSet><vim:pathSet>summary.totalVmCount</vim:pathSet><vim:pathSet>hardware.cpuInfo.numCpuCores</vim:pathSet><vim:pathSet>hardware.cpuInfo.hz</vim:pathSet><vim:pathSet>hardware.memorySize</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="HostSystem">$hm</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
        // 单独查 datastore
        fun hostDs(pc: String, hm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>HostSystem</vim:type><vim:pathSet>datastore</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="HostSystem">$hm</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
        // 通过 datastoreSystem 查
        fun dsSystem(pc: String, hm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>HostSystem</vim:type><vim:pathSet>configManager.datastoreSystem</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="HostSystem">$hm</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
        // 全量 VM 查询（不指定 MOID，类似官方用 ContainerView）
        fun allVms(pc: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>VirtualMachine</vim:type><vim:pathSet>name</vim:pathSet><vim:pathSet>runtime.powerState</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="HostSystem">ha-host</vim:obj><vim:selectSet xsi:type="TraversalSpec"><vim:type>HostSystem</vim:type><vim:path>vm</vim:path><vim:skip>false</vim:skip></vim:selectSet></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
        fun hostVms(pc: String, hm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>HostSystem</vim:type><vim:pathSet>vm</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="HostSystem">$hm</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
        fun vmProps(pc: String, vm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>VirtualMachine</vim:type><vim:pathSet>name</vim:pathSet><vim:pathSet>runtime.powerState</vim:pathSet><vim:pathSet>config.hardware.numCPU</vim:pathSet><vim:pathSet>config.hardware.memoryMB</vim:pathSet><vim:pathSet>config.guestFullName</vim:pathSet><vim:pathSet>guest.ipAddress</vim:pathSet><vim:pathSet>summary.quickStats.overallCpuUsage</vim:pathSet><vim:pathSet>summary.quickStats.guestMemoryUsage</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="VirtualMachine">$vm</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
        fun dsProps(pc: String, ds: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>Datastore</vim:type><vim:pathSet>summary.capacity</vim:pathSet><vim:pathSet>summary.freeSpace</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="Datastore">$ds</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
    }
}