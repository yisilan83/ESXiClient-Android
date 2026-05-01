package dev.esxiclient.app.data

import dev.esxiclient.app.model.*

object MockData {

    val hostInfo = HostInfo(
        hostname = "esxi-homelab",
        hostAddress = "192.168.1.100",
        cpuUsagePercent = 23,
        memoryUsagePercent = 45,
        totalMemoryGB = 32,
        uptimeSeconds = 1324800L,
        runningVmCount = 3,
        totalVmCount = 5,
        storageUsedGB = 357L,
        storageTotalGB = 500L
    )

    val vmList = listOf(
        VmInfo(
            id = "vm-42",
            name = "Ubuntu Server 22.04",
            powerState = PowerState.POWERED_ON,
            cpuCount = 4,
            cpuUsagePercent = 35,
            memoryMiB = 4096L,
            memoryUsedMiB = 2150L,
            guestOs = "Ubuntu Linux (64-bit)",
            ipAddress = "192.168.1.101",
            uptimeSeconds = 1296000L,
            disks = listOf(
                DiskInfo("Hard disk 1", 100L, 45L),
                DiskInfo("Hard disk 2", 50L, 12L)
            )
        ),
        VmInfo(
            id = "vm-43",
            name = "Windows Server 2019",
            powerState = PowerState.POWERED_OFF,
            cpuCount = 8,
            cpuUsagePercent = 0,
            memoryMiB = 8192L,
            memoryUsedMiB = 0L,
            guestOs = "Microsoft Windows Server 2019 (64-bit)",
            ipAddress = null,
            uptimeSeconds = 0L,
            disks = listOf(
                DiskInfo("Hard disk 1", 200L, 0L)
            )
        ),
        VmInfo(
            id = "vm-44",
            name = "Debian 12 - Web",
            powerState = PowerState.POWERED_ON,
            cpuCount = 2,
            cpuUsagePercent = 18,
            memoryMiB = 2048L,
            memoryUsedMiB = 890L,
            guestOs = "Debian Linux 12 (64-bit)",
            ipAddress = "192.168.1.102",
            uptimeSeconds = 864000L,
            disks = listOf(
                DiskInfo("Hard disk 1", 40L, 15L)
            )
        ),
        VmInfo(
            id = "vm-45",
            name = "macOS Sonoma",
            powerState = PowerState.SUSPENDED,
            cpuCount = 4,
            cpuUsagePercent = 0,
            memoryMiB = 8192L,
            memoryUsedMiB = 0L,
            guestOs = "Apple macOS 14 (64-bit)",
            ipAddress = null,
            uptimeSeconds = 0L,
            disks = listOf(
                DiskInfo("Hard disk 1", 120L, 68L)
            )
        ),
        VmInfo(
            id = "vm-46",
            name = "CentOS 7 - DB",
            powerState = PowerState.POWERED_ON,
            cpuCount = 4,
            cpuUsagePercent = 62,
            memoryMiB = 6144L,
            memoryUsedMiB = 4870L,
            guestOs = "CentOS Linux 7 (64-bit)",
            ipAddress = "192.168.1.103",
            uptimeSeconds = 2592000L,
            disks = listOf(
                DiskInfo("Hard disk 1", 80L, 52L),
                DiskInfo("Hard disk 2", 200L, 145L)
            )
        )
    )

    fun getVmById(id: String): VmInfo? = vmList.find { it.id == id }
}