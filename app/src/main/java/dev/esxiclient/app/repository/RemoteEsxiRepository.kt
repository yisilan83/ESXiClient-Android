package dev.esxiclient.app.repository

import android.util.Log
import dev.esxiclient.app.model.*
import dev.esxiclient.app.network.RetrofitClient

class RemoteEsxiRepository(
    private val host: String,
    private val sessionId: String
) : EsxiRepository {

    private suspend fun callSoap(soapXml: String, label: String = ""): String {
        Log.d("R", "$label → ${soapXml.take(120)}")
        val r = RetrofitClient.service.executeSoap(host, soapXml, sessionId)
        val body = r.body?.string() ?: ""
        r.close()
        Log.d("R", "---$label RESPONSE [${body.length}B]---\n$body\n---END---")
        return body
    }

    private var pcMoid = ""
    private var hostSystems: List<String> = emptyList()

    private suspend fun init(): Boolean {
        if (pcMoid.isNotBlank() && hostSystems.isNotEmpty()) return true
        try {
            // 1. ServiceContent - 完整输出
            val sc = callSoap(B.serviceContent(), "SC")
            pcMoid = rx1("propertyCollector", sc)
            val rf = rx1("rootFolder", sc)
            Log.d("R", "pc=$pcMoid rf=$rf")
            if (pcMoid.isBlank() || rf.isBlank()) return false

            // 从 SC 中直接找 host-* MOID
            hostSystems = moids(sc).filter { it.startsWith("host-") }
            Log.d("R", "SC中的host: $hostSystems")

            // 2. 如果 SC 中找不到 host，尝试查询 Datacenter
            val dcs = moids(sc).filter { it.startsWith("datacenter-") }
            val foldersToTry = if (dcs.isNotEmpty()) dcs else listOf(rf)

            for (fid in foldersToTry.take(5)) {
                if (hostSystems.isNotEmpty()) break
                try {
                    val xml = callSoap(B.childEntity(pcMoid, fid), "FOLDER $fid")
                    // 即使有 missingSet fault，也尝试从 objects 块中找
                    val kids = moids(xml)
                    // ManagedObjectReference 嵌套提取
                    kids += Regex("""<ManagedObjectReference type="\w+">(\w+-\d+)</ManagedObjectReference>""").findAll(xml).map { it.groupValues[1] }
                    val uniq = kids.distinct()
                    Log.d("R", "  $fid → $uniq")
                    for (k in uniq) {
                        when {
                            k.startsWith("host-") -> hostSystems = hostSystems + k
                            k.startsWith("datacenter-") || k.startsWith("folder-") || k.startsWith("group-") -> {
                                // 递归一级
                                try {
                                    val x2 = callSoap(B.childEntity(pcMoid, k), "FOLDER $k")
                                    val k2 = moids(x2) + Regex("""<ManagedObjectReference type="\w+">(\w+-\d+)</ManagedObjectReference>""").findAll(x2).map { it.groupValues[1] }
                                    Log.d("R", "    $k → ${k2.distinct()}")
                                    hostSystems = hostSystems + k2.filter { it.startsWith("host-") }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. 最后的备用：硬编码尝试 host-1 ~ host-30
            if (hostSystems.isEmpty()) {
                Log.d("R", "暴力搜索 host-1 ~ host-30...")
                for (i in 1..30) {
                    try {
                        val t = callSoap(B.hostProps(pcMoid, "host-$i"), "TEST host-$i")
                        if (!t.contains("ManagedObjectNotFound") && !t.contains("has already been deleted")) {
                            Log.d("R", "host-$i 有效!")
                            hostSystems = hostSystems + "host-$i"
                            break
                        }
                    } catch (_: Exception) {}
                }
            }

            Log.d("R", "最终 hosts=$hostSystems")
            return hostSystems.isNotEmpty()
        } catch (e: Exception) {
            Log.e("R", "init err ${e.message}", e)
            return false
        }
    }

    private fun rx1(tag: String, xml: String): String {
        val m = Regex("""<$tag type="\w+">([^<]+)</$tag>""").find(xml)
            ?: Regex("""<$tag[^>]*>([^<]+)</$tag>""").find(xml)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun moids(xml: String): MutableList<String> {
        val out = mutableListOf<String>()
        out += Regex("""<value>(\w+-\d+)</value>""").findAll(xml).map { it.groupValues[1] }
        out += Regex("""<obj type="\w+">(\w+-\d+)</obj>""").findAll(xml).map { it.groupValues[1] }
        return out
    }

    override suspend fun getHostInfo(): HostInfo {
        if (!init()) return emptyHost("Unknown")
        val hm = hostSystems.first()
        try {
            val px = callSoap(B.hostProps(pcMoid, hm), "HOSTPROPS")
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
        } catch (e: Exception) {
            Log.e("R", "ghi err ${e.message}", e)
            return emptyHost("Unknown")
        }
    }

    override suspend fun getVmList(): List<VmInfo> {
        if (!init()) return emptyList()
        val vms = mutableListOf<VmInfo>()
        try {
            val vx = callSoap(B.hostVms(pcMoid, hostSystems.first()), "VMLIST")
            for (vid in moids(vx).filter { it.startsWith("vm-") }.take(30)) {
                try {
                    val vp = callSoap(B.vmProps(pcMoid, vid), "VM $vid")
                    vms.add(VmInfo(id = vid, name = prop(vp, "name").ifBlank { vid },
                        powerState = when (prop(vp, "powerState")) {
                            "poweredOn" -> PowerState.POWERED_ON; "suspended" -> PowerState.SUSPENDED; else -> PowerState.POWERED_OFF
                        },
                        cpuCount = prop(vp, "numCPU").filter(Char::isDigit).toIntOrNull() ?: 1,
                        memoryMiB = prop(vp, "memoryMB").filter(Char::isDigit).toLongOrNull() ?: 0L,
                        cpuUsagePercent = (prop(vp, "overallCpuUsage").filter(Char::isDigit).toIntOrNull() ?: 0).coerceIn(0, 100),
                        memoryUsedMiB = prop(vp, "guestMemoryUsage").filter(Char::isDigit).toLongOrNull() ?: 0L,
                        guestOs = prop(vp, "guestFullName"), ipAddress = prop(vp, "ipAddress").ifBlank { null }))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.e("R", "gvl err ${e.message}", e) }
        return vms
    }

    override suspend fun getVmById(vmId: String) = getVmList().find { it.id == vmId }
    override suspend fun toggleVmPower(vmId: String) = false

    private fun tag(xml: String, t: String): String { for (p in listOf("", "vim:", "ns0:")) { val v = xml.substringAfter("<${p}$t>").substringBefore("</${p}$t>"); if (v != xml) return v }; return "" }
    private fun prop(xml: String, n: String): String = Regex("""<name>$n</name>\s*<val[^>]*>(.*?)</val>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim() ?: ""
    private fun emptyHost(ver: String) = HostInfo(host, host, version = ver, cpuUsagePercent = 0, memoryUsagePercent = 0, totalMemoryGB = 0, uptimeSeconds = 0, runningVmCount = 0, totalVmCount = 0, storageUsedGB = 0, storageTotalGB = 0)
    private fun buildHost(ver: String, cpu: Int, mem: Int, memGb: Long, up: Long, vms: Int, su: Long, st: Long) = HostInfo(host, host, version = Regex("""(\d+\.\d+(?:\.\d+)?)""").find(ver)?.value ?: ver.ifBlank { "Unknown" }, cpuUsagePercent = cpu, memoryUsagePercent = mem, totalMemoryGB = memGb.toInt(), uptimeSeconds = up, runningVmCount = 0, totalVmCount = vms, storageUsedGB = su, storageTotalGB = st)

    // === SOAP ===
    private object B {
        fun serviceContent() = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrieveServiceContent><vim:_this type="ServiceInstance">ServiceInstance</vim:_this></vim:RetrieveServiceContent></soapenv:Body>
</soapenv:Envelope>"""
        fun childEntity(pc: String, fid: String) = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body><vim:RetrievePropertiesEx><vim:_this type="PropertyCollector">$pc</vim:_this><vim:specSet><vim:propSet><vim:type>Folder</vim:type><vim:pathSet>childEntity</vim:pathSet></vim:propSet><vim:objectSet><vim:obj type="Folder">$fid</vim:obj></vim:objectSet></vim:specSet><vim:options/></vim:RetrievePropertiesEx></soapenv:Body>
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