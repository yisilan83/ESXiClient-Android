package dev.esxiclient.app.data

import dev.esxiclient.app.model.*

object MockData {

    val hostInfo = HostInfo(
        hostname = "esxi-homelab",
        hostAddress = "192.168.1.100",
        cpuUsagePercent = 23,
        memoryUsagePercent = 45,
        totalMemoryGB = 32,
        uptimeSeconds = 1324800,
        runningVmCount = 3,
        totalVmCount = 5,
        storageUsedGB = 357,
        storageTotalGB = 500
    )

    val vmList = listOf(
        VmInfo(
            id = "vm-42",
            name = "Ubuntu Server 22.04",
            powerState = PowerState.POWERED_ON,
            cpuCount = 4,
            cpuUsagePercent = 35,
            memoryMiB = 4096,
            memoryUsedMiB = 2150,
            guestOs = "Ubuntu Linux (64-bit)",
            ipAddress = "192.168.1.101",
            uptimeSeconds = 1296000,
            disks = listOf(
                DiskInfo("Hard disk 1", 100, 45),
                DiskInfo("Hard disk 2", 50, 12)
            )
        ),
        VmInfo(
            id = "vm-43",
            name = "Windows Server 2019",
            powerState = PowerState.POWERED_OFF,
            cpuCount = 8,
            cpuUsagePercent = 0,
            memoryMiB = 8192,
            memoryUsedMiB = 0,
            guestOs = "Microsoft Windows Server 2019 (64-bit)",
            ipAddress = null,
            uptimeSeconds = 0,
            disks = listOf(
                DiskInfo("Hard disk 1", 200, 0)
            )
        ),
        VmInfo(
            id = "vm-44",
            name = "Debian 12 - Web",
            powerState = PowerState.POWERED_ON,
            cpuCount = 2,
            cpuUsagePercent = 18,
            memoryMiB = 2048,
            memoryUsedMiB = 890,
            guestOs = "Debian Linux 12 (64-bit)",
            ipAddress = "192.168.1.102",
            uptimeSeconds = 864000,
            disks = listOf(
                DiskInfo("Hard disk 1", 40, 15)
            )
        ),
        VmInfo(
            id = "vm-45",
            name = "macOS Sonoma",
            powerState = PowerState.SUSPENDED,
            cpuCount = 4,
            cpuUsagePercent = 0,
            memoryMiB = 8192,
            memoryUsedMiB = 0,
            guestOs = "Apple macOS 14 (64-bit)",
            ipAddress = null,
            uptimeSeconds = 0,
            disks = listOf(
                DiskInfo("Hard disk 1", 120, 68)
            )
        ),
        VmInfo(
            id = "vm-46",
            name = "CentOS 7 - DB",
            powerState = PowerState.POWERED_ON,
            cpuCount = 4,
            cpuUsagePercent = 62,
            memoryMiB = 6144,
            memoryUsedMiB = 4870,
            guestOs = "CentOS Linux 7 (64-bit)",
            ipAddress = "192.168.1.103",
            uptimeSeconds = 2592000,
            disks = listOf(
                DiskInfo("Hard disk 1", 80, 52),
                DiskInfo("Hard disk 2", 200, 145)
            )
        )
    )

    fun getVmById(id: String): VmInfo? = vmList.find { it.id == id }
}
