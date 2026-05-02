package dev.esxiclient.app.repository

import dev.esxiclient.app.model.HostInfo
import dev.esxiclient.app.model.VmInfo

interface EsxiRepository {
    suspend fun getHostInfo(): HostInfo
    suspend fun getVmList(): List<VmInfo>
    suspend fun getVmById(vmId: String): VmInfo?
    suspend fun toggleVmPower(vmId: String): Boolean

    /** Priority used by the protocol negotiator: higher = better. */
    val priority: Int
    /** Human-readable protocol name for debugging. */
    val protocolName: String
}
