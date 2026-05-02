package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {

    private suspend fun callSoap(soapXml: String, label: String = ""): String {
        Log.d("R", "→$label ${soapXml.take(130)}")
        val r = RetrofitClient.service.executeSoap(host, soapXml, sessionId)
        val body = r.body?.string() ?: ""
        r.close()
        Log.d("R", "←$label [${body.length}B]\n$body\n---")
        return body
    }

    private var pcMoid = ""
    private var hostSystems: List<String> = emptyList()

    private suspend fun init(): Boolean {
        if (pcMoid.isNotBlank() && hostSystems.isNotEmpty()) return true
        try {
            val sc = callSoap(B.serviceContent(), "SC")
            pcMoid = rx1("propertyCollector", sc)
            Log.d("R", "pc=$pcMoid")
            if (pcMoid.isBlank()) return false

            val searchIndex = rx1("searchIndex", sc)  // ha-searchindex
            Log.d("R", "searchIndex=$searchIndex")

            // 方法1：SearchIndex.FindByDnsName
            if (searchIndex.isNotBlank()) {
                val dns = host.replace("https://", "").replace(":8443", "").split("/")[0]
                val ip = Regex("""\d+\.\d+\.\d+\.\d+""").find(dns)?.value ?: dns
                Log.d("R", "SearchIndex.FindByDnsName($ip, false)")
                val xml = callSoap(B.findByDnsName(searchIndex, ip, false), "DNS")
                // 响应格式：<returnval><HostSystem type="HostSystem">host-xxx</HostSystem>...
                val hs = Regex("""<HostSystem type="\w+">(\w+-\d+)</HostSystem>""").findAll(xml).map { it.groupValues[1] }.toList()
                Log.d("R", "FindByDnsName结果: $hs")
                if (hs.isNotEmpty()) { hostSystems = hs; return true }
            }

            // 方法2：SearchIndex.FindByIp
            if (searchIndex.isNotBlank()) {
                val ip = try { java.net.InetAddress.getByName(host.replace("https://","").replace(":8443","").split("/")[0]).hostAddress } catch (_: Exception) { "" }
                if (ip.isNotBlank()) {
                    Log.d("R", "SearchIndex.FindByIp($ip, false)")
                    val xml = callSoap(B.findByIp(searchIndex, ip, false), "IP")
                    val hs = Regex("""<HostSystem type="\w+">(\w+-\d+)</HostSystem>""").findAll(xml).map { it.groupValues[1] }.toList()
                    Log.d("R", "FindByIp结果: $hs")
                    if (hs.isNotEmpty()) { hostSystems = hs; return true }
                }
            }

            // 方法3：SearchIndex.FindAllByDnsName
            if (searchIndex.isNotBlank()) {
                val xml = callSoap(B.findAllByDnsName(searchIndex, "", false), "ALLDNS")
                // 查找所有 host-
                val all = Regex("""<HostSystem type="\w+">(\w+-\d+)</HostSystem>""").findAll(xml).map { it.groupValues[1] }.toList()
                Log.d("R", "FindAllByDnsName结果: $all")
                if (all.isNotEmpty()) { hostSystems = all; return true }
            }

            Log.d("R", "所有SearchIndex方法都未找到HostSystem")
            return false
        } catch (e: Exception) { Log.e("R", "init err ${e.message}", e); return false }
    }

    private fun rx1(tag: String, xml: String): String {
        val m = Regex("""<$tag type="\w+">([^<]+)</$tag>""").find(xml)
            ?: Regex("""<$tag[^>]*>([^<]+)</$tag>""").find(xml)
        return m?.groupValues?.get(1) ?: ""
    }

    override suspend fun getHostInfo(): HostInfo {
        if (!init()) return emptyHost("Unknown")
        val hm = hostSystems.first()
        try {
            val px = callSoap(B.hostProps(pcMoid, hm), "HOST")
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
            val vmMoids = moids(prop(px, "vm")).filter { it.startsWith("vm-") }
            val totalVMs = vmMoids.size.coerceAtLeast(prop(px, "totalVmCount").filter(Char::isDigit).toIntOrNull() ?: 0)

            var su = 0L; var st = 0L
            for (dm in moids(prop(px, "datastore")).filter { it.startsWith("datastore-") }.take(10)) {
                try {
                    val dx = callSoap(B.dsProps(pcMoid, dm), "DS")
                    val c = prop(dx, "capacity").filter(Char::isDigit).toLongOrNull() ?: 0L
                    val f = prop(dx, "freeSpace").filter(Char::isDigit).toLongOrNull() ?: 0L
                    st += c / (1024*1024*1024); su += (c - f) / (1024*1024*1024)
                } catch (_: Exception) {}
            }

            Log.d("R", "CPU=$cpuUsage% MEM=$memUsage% MEMGB=$totalMemGb UP=${uptime}s VMs=$totalVMs DS=$su/$st GB")
            return buildHost(ver, cpuUsage, memUsage, totalMemGb, uptime, totalVMs, su, st)
        } catch (e: Exception) { Log.e("R", "ghi err ${e.message}", e); return emptyHost("Unknown") }
    }

    override suspend fun getVmList(): List<VmInfo> {
        if (!init()) return emptyList()
        val vms = mutableListOf<VmInfo>()
        try {
            val vx = callSoap(B.hostVms(pcMoid, hostSystems.first()), "VMLIST")
            for (vid in moids(vx).filter { it.startsWith("vm-") }.take(30)) {
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
        } catch (e: Exception) { Log.e("R", "gvl err ${e.message}", e) }
        return vms
    }

    override suspend fun getVmById(vmId: String) = getVmList().find { it.id == vmId }
    override suspend fun toggleVmPower(vmId: String) = false

    private fun tag(xml: String, t: String): String { for (p in listOf("","vim:","ns0:")) { val v = xml.substringAfter("<${p}$t>").substringBefore("</${p}$t>"); if (v != xml) return v }; return "" }
    private fun prop(xml: String, n: String): String = Regex("""<name>$n</name>\s*<val[^>]*>(.*?)</val>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim() ?: ""
    private fun moids(xml: String) = Regex("""<value>(\w+-\d+)</value>""").findAll(xml).map { it.groupValues[1] }.toMutableList()
    private fun emptyHost(ver: String) = HostInfo(host, host, version = ver, cpuUsagePercent = 0, memoryUsagePercent = 0, totalMemoryGB = 0, uptimeSeconds = 0, runningVmCount = 0, totalVmCount = 0, storageUsedGB = 0, storageTotalGB = 0)
    private fun buildHost(ver: String, cpu: Int, mem: Int, memGb: Long, up: Long, vms: Int, su: Long, st: Long) = HostInfo(host, host, version = Regex("""(\d+\.\d+(?:\.\d+)?)""").find(ver)?.value ?: ver.ifBlank { "Unknown" }, cpuUsagePercent = cpu, memoryUsagePercent = mem, totalMemoryGB = memGb.toInt(), uptimeSeconds = up, runningVmCount = 0, totalVmCount = vms, storageUsedGB = su, storageTotalGB = st)

    private object B {
        fun serviceContent() = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrieveServiceContent><vim:_this type="ServiceInstance">ServiceInstance</vim:_this></vim:RetrieveServiceContent></soapenv:Body>
</soapenv:Envelope>"""
        // SearchIndex methods
        fun findByDnsName(si: String, dns: String, vmSearch: Boolean) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:FindByDnsName><vim:_this type="SearchIndex">$si</vim:_this><vim:dnsName>$dns</vim:dnsName><vim:vmSearch>$vmSearch</vim:vmSearch></vim:FindByDnsName></soapenv:Body>
</soapenv:Envelope>"""
        fun findByIp(si: String, ip: String, vmSearch: Boolean) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:FindByIp><vim:_this type="SearchIndex">$si</vim:_this><vim:ip>$ip</vim:ip><vim:vmSearch>$vmSearch</vim:vmSearch></vim:FindByIp></soapenv:Body>
</soapenv:Envelope>"""
        fun findAllByDnsName(si: String, dns: String, vmSearch: Boolean) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:FindAllByDnsName><vim:_this type="SearchIndex">$si</vim:_this><vim:dnsName>$dns</vim:dnsName><vim:vmSearch>$vmSearch</vim:vmSearch></vim:FindAllByDnsName></soapenv:Body>
</soapenv:Envelope>"""
        fun hostProps(pc: String, hm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>HostSystem</vim:type><vim:pathSet>summary.quickStats.overallCpuUsage</vim:pathSet><vim:pathSet>summary.quickStats.overallMemoryUsage</vim:pathSet><vim:pathSet>summary.quickStats.uptime</vim:pathSet><vim:pathSet>summary.totalVmCount</vim:pathSet><vim:pathSet>summary.datastore</vim:pathSet><vim:pathSet>hardware.cpuInfo.numCpuCores</vim:pathSet><vim:pathSet>hardware.cpuInfo.hz</vim:pathSet><vim:pathSet>hardware.memorySize</vim:pathSet><vim:pathSet>vm</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="HostSystem">$hm</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
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