package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {

    override val priority: Int = 10
    override val protocolName: String = "SOAP"

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

    private fun rx1(tag: String, xml: String): String {
        val m = Regex("""<$tag type="\\w+">([^<]+)</$tag>""").find(xml)
            ?: Regex("""<$tag[^>]*>([^<]+)</$tag>""").find(xml)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun moidList(xml: String, type: String) =
        Regex("""<obj type="$type">(\w+-\d+)</obj>""").findAll(xml).map { it.groupValues[1] }.toList().distinct()

    override suspend fun getHostInfo(): HostInfo {
        if (!init()) return emptyHost("Unknown")
        try {
            val px = callSoap(B.hostProps(pcMoid, "ha-host"), "HOST")
            if (px.contains("ManagedObjectNotFound")) return emptyHost("Unknown")

            val ver = callSoap(B.serviceContent(), "SC2").let { tag(it, "fullName") }.ifBlank { "Unknown" }

            val cpuMhz   = prop(px, "overallCpuUsage").filter(Char::isDigit).toLongOrNull() ?: 0L
            val cpuMhz_h  = prop(px, "cpuMhz").filter(Char::isDigit).toLongOrNull()
            val cpuCores_h = prop(px, "numCpuCores").filter(Char::isDigit).toIntOrNull()
            val cpuHz    = prop(px, "hz").filter(Char::isDigit).toLongOrNull()

            val cpuUsage = when {
                cpuMhz_h != null && cpuCores_h != null && cpuMhz_h > 0L && cpuCores_h > 0 -> ((cpuMhz * 100L) / (cpuMhz_h * cpuCores_h)).toInt()
                cpuHz != null && cpuHz > 0 && cpuCores_h != null && cpuCores_h > 0 -> ((cpuMhz * 1_000_000L * 100L) / (cpuHz * cpuCores_h)).toInt()
                else -> 0
            }.coerceIn(0, 100)

            val memMbRaw = prop(px, "overallMemoryUsage").filter(Char::isDigit).toLongOrNull() ?: 0L
            val memBytes = prop(px, "memorySize").filter(Char::isDigit).toLongOrNull() ?: 0L
            val totalMemGb = memBytes / (1024*1024*1024)
            val memUsage = if (totalMemGb > 0) ((memMbRaw * 100L) / (totalMemGb * 1024)).toInt().coerceIn(0, 100) else 0

            val uptime = prop(px, "uptime").filter(Char::isDigit).toLongOrNull() ?: 0L

            Log.d("R","CPU=$cpuUsage% MEM=$memUsage% MEMGB=$totalMemGb UP=${uptime}s cpuMhz=$cpuMhz cpuMhz_h=$cpuMhz_h cores=$cpuCores_h hz=$cpuHz memBytes=$memBytes")
            return buildHost(ver, cpuUsage, memUsage, totalMemGb, uptime, 0, 0L, 0L)
        } catch (e: Exception) { Log.e("R","ghi err ${e.message}",e); return emptyHost("Unknown") }
    }

    override suspend fun getVmList(): List<VmInfo> {
        if (!init()) return emptyList()
        val vms = mutableListOf<VmInfo>()
        try {
            val cvXml = callSoap(B.createContainerView(pcMoid), "CV")
            val cvMoid = rx1("obj", cvXml)
            Log.d("R","cvMoid=$cvMoid")

            if (cvMoid.isNotBlank()) {
                val vx = callSoap(B.vmViaContainerView(pcMoid, cvMoid), "VMVIA")
                val vmIds = moidList(vx, "VirtualMachine")
                Log.d("R","VMVIA: ${vmIds.size} VMs: $vmIds")
                if (vmIds.isNotEmpty()) {
                    for (vid in vmIds.take(30)) {
                        try { vms.add(parseVm(vid, callSoap(B.vmProps(pcMoid, vid), "VM$vid"))) } catch (_: Exception) {}
                    }
                    try { callSoap(B.destroyContainerView(pcMoid, cvMoid), "DCV") } catch (_: Exception) {}
                    return vms
                }
                try { callSoap(B.destroyContainerView(pcMoid, cvMoid), "DCV") } catch (_: Exception) {}
            }

            val hv = callSoap(B.hostVms(pcMoid, "ha-host"), "HOSTVM")
            val vmIds = moidList(hv, "VirtualMachine")
            Log.d("R","HOSTVM: ${vmIds.size} VMs")
            for (vid in vmIds.take(30)) {
                try { vms.add(parseVm(vid, callSoap(B.vmProps(pcMoid, vid), "VM$vid"))) } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.e("R","gvl err ${e.message}",e) }
        return vms
    }

    private fun parseVm(vid: String, vp: String) = VmInfo(
        id = vid, name = prop(vp, "name").ifBlank { vid },
        powerState = when (prop(vp, "powerState")) {"poweredOn"->PowerState.POWERED_ON;"suspended"->PowerState.SUSPENDED;else->PowerState.POWERED_OFF},
        cpuCount = prop(vp, "numCPU").filter(Char::isDigit).toIntOrNull()?:1,
        memoryMiB = prop(vp, "memoryMB").filter(Char::isDigit).toLongOrNull()?:0L,
        cpuUsagePercent = (prop(vp, "overallCpuUsage").filter(Char::isDigit).toIntOrNull()?:0).coerceIn(0,100),
        memoryUsedMiB = prop(vp, "guestMemoryUsage").filter(Char::isDigit).toLongOrNull()?:0L,
        guestOs = prop(vp, "guestFullName"), ipAddress = prop(vp, "ipAddress").ifBlank { null })

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
        fun hostProps(pc: String, hm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>HostSystem</vim:type><vim:pathSet>summary.quickStats.overallCpuUsage</vim:pathSet><vim:pathSet>summary.quickStats.overallMemoryUsage</vim:pathSet><vim:pathSet>summary.quickStats.uptime</vim:pathSet><vim:pathSet>summary.hardware.cpuMhz</vim:pathSet><vim:pathSet>summary.hardware.numCpuCores</vim:pathSet><vim:pathSet>hardware.cpuInfo.numCpuCores</vim:pathSet><vim:pathSet>hardware.cpuInfo.hz</vim:pathSet><vim:pathSet>hardware.memorySize</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="HostSystem">$hm</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
        fun createContainerView(pc: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:CreateContainerView><vim:_this type="ViewManager">ViewManager</vim:_this><vim:container type="Folder">ha-folder-root</vim:container><vim:type>VirtualMachine</vim:type><vim:recursive>true</vim:recursive></vim:CreateContainerView></soapenv:Body>
</soapenv:Envelope>"""
        fun vmViaContainerView(pc: String, cv: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>VirtualMachine</vim:type><vim:pathSet>name</vim:pathSet><vim:pathSet>runtime.powerState</vim:pathSet><vim:pathSet>config.hardware.numCPU</vim:pathSet><vim:pathSet>config.hardware.memoryMB</vim:pathSet><vim:pathSet>config.guestFullName</vim:pathSet><vim:pathSet>guest.ipAddress</vim:pathSet><vim:pathSet>summary.quickStats.overallCpuUsage</vim:pathSet><vim:pathSet>summary.quickStats.guestMemoryUsage</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="ContainerView">$cv</vim:obj><vim:skip>false</vim:skip><vim:selectSet xsi:type="TraversalSpec"><vim:type>ContainerView</vim:type><vim:path>view</vim:path><vim:skip>false</vim:skip></vim:selectSet></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
        fun destroyContainerView(pc: String, cv: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:DestroyView><vim:_this type="ViewManager">ViewManager</vim:_this><vim:containerView type="ContainerView">$cv</vim:containerView></vim:DestroyView></soapenv:Body>
</soapenv:Envelope>"""
        fun hostVms(pc: String, hm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>HostSystem</vim:type><vim:pathSet>vm</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="HostSystem">$hm</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
        fun vmProps(pc: String, vm: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>VirtualMachine</vim:type><vim:pathSet>name</vim:pathSet><vim:pathSet>runtime.powerState</vim:pathSet><vim:pathSet>config.hardware.numCPU</vim:pathSet><vim:pathSet>config.hardware.memoryMB</vim:pathSet><vim:pathSet>config.guestFullName</vim:pathSet><vim:pathSet>guest.ipAddress</vim:pathSet><vim:pathSet>summary.quickStats.overallCpuUsage</vim:pathSet><vim:pathSet>summary.quickStats.guestMemoryUsage</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="VirtualMachine">$vm</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
</soapenv:Envelope>"""
    }
}
