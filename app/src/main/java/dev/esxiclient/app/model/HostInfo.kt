package dev.esxiclient.app.model

data class HostInfo(
    val hostname: String,
    val hostAddress: String,
    val cpuUsagePercent: Int,
    val memoryUsagePercent: Int,
    val totalMemoryGB: Int,
    val uptimeSeconds: Long,
    val runningVmCount: Int,
    val totalVmCount: Int,
    val storageUsedGB: Long,
    val storageTotalGB: Long
) {
    val uptimeText: String
        get() {
            val days = uptimeSeconds / 86400
            val hours = (uptimeSeconds % 86400) / 3600
            return if (days > 0) "${days}天 ${hours}小时" else "${hours}小时"
        }

    val storageUsagePercent: Int
        get() = if (storageTotalGB > 0) ((storageUsedGB * 100) / storageTotalGB).toInt() else 0
}
