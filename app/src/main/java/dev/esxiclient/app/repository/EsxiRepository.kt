package dev.esxiclient.app.repository

import dev.esxiclient.app.model.HostInfo
import dev.esxiclient.app.model.VmInfo

interface EsxiRepository {
    suspend fun getHostInfo(): HostInfo
    suspend fun getVmList(): List<VmInfo>
    suspend fun getVmById(vmId: String): VmInfo?
    suspend fun toggleVmPower(vmId: String): Boolean
}