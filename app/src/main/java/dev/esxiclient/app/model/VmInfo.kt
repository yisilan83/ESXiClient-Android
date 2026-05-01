package dev.esxiclient.app.model

enum class PowerState {
    POWERED_ON,
    POWERED_OFF,
    SUSPENDED;

    fun displayText(): String = when (this) {
        POWERED_ON -> "运行中"
        POWERED_OFF -> "已停止"
        SUSPENDED -> "已挂起"
    }
}

data class VmInfo(
    val id: String,
    val name: String,
    val powerState: PowerState,
    val cpuCount: Int,
    val cpuUsagePercent: Int,
    val memoryMiB: Long,
    val memoryUsedMiB: Long,
    val guestOs: String,
    val ipAddress: String? = null,
    val uptimeSeconds: Long = 0,
    val disks: List<DiskInfo> = emptyList()
) {
    val memoryUsagePercent: Int
        get() = if (memoryMiB > 0) ((memoryUsedMiB * 100) / memoryMiB).toInt() else 0

    val uptimeText: String
        get() {
            val days = uptimeSeconds / 86400
            val hours = (uptimeSeconds % 86400) / 3600
            return if (days > 0) "${days}天 ${hours}小时" else "${hours}小时"
        }
}

data class DiskInfo(
    val label: String,
    val capacityGB: Long,
    val usedGB: Long
) {
    val usagePercent: Int
        get() = if (capacityGB > 0) ((usedGB * 100) / capacityGB).toInt() else 0
}
