package dev.esxiclient.app.repository

import dev.esxiclient.app.data.MockData
import dev.esxiclient.app.model.HostInfo
import dev.esxiclient.app.model.PowerState
import dev.esxiclient.app.model.VmInfo
import kotlinx.coroutines.delay

interface EsxiRepository {
    suspend fun getHostInfo(): HostInfo
    suspend fun getVmList(): List<VmInfo>
    suspend fun getVmById(vmId: String): VmInfo?
    suspend fun toggleVmPower(vmId: String): Boolean
}

object MockEsxiRepository : EsxiRepository {
    private val _vmList = MockData.vmList.toMutableList()

    override suspend fun getHostInfo(): HostInfo {
        delay(500) // Simulate network delay
        return MockData.hostInfo
    }

    override suspend fun getVmList(): List<VmInfo> {
        delay(500)
        return _vmList.toList()
    }

    override suspend fun getVmById(vmId: String): VmInfo? {
        delay(300)
        return _vmList.find { it.id == vmId }
    }

    override suspend fun toggleVmPower(vmId: String): Boolean {
        delay(800) // Simulate operation delay
        val index = _vmList.indexOfFirst { it.id == vmId }
        if (index != -1) {
            val vm = _vmList[index]
            val newState = if (vm.powerState == PowerState.POWERED_ON) PowerState.POWERED_OFF else PowerState.POWERED_ON
            
            // Simulate resource changes based on power state
            val updatedVm = if (newState == PowerState.POWERED_ON) {
                vm.copy(
                    powerState = newState,
                    cpuUsagePercent = (10..60).random(),
                    memoryUsedMiB = (vm.memoryMiB * (20..70).random() / 100),
                    ipAddress = "192.168.1.${(100..200).random()}",
                    uptimeSeconds = 0
                )
            } else {
                vm.copy(
                    powerState = newState,
                    cpuUsagePercent = 0,
                    memoryUsedMiB = 0,
                    ipAddress = null,
                    uptimeSeconds = 0
                )
            }
            
            _vmList[index] = updatedVm
            return true
        }
        return false
    }
}