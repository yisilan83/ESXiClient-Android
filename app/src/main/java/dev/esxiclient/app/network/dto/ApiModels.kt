package dev.esxiclient.app.network.dto

data class VmListResponse(
    val value: List<VmDto> = emptyList()
)

data class VmDto(
    val vm: String,
    val name: String,
    val power_state: String?,
    val cpu_count: Int?,
    val memory_size_MiB: Long?
)