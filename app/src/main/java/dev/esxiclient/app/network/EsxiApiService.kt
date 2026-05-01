package dev.esxiclient.app.network

import dev.esxiclient.app.network.dto.VmListResponse
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface EsxiApiService {
    
    // ==================== 认证 (Auth) ====================
    @POST("api/session")
    suspend fun createSession(
        @Header("Authorization") basicAuth: String
    ): Response<String>

    @DELETE("api/session")
    suspend fun deleteSession(
        @Header("vmware-api-session-id") sessionId: String
    ): Response<Unit>

    // ==================== 主机信息 (Host Info) ====================
    @GET("api/appliance/system/version")
    suspend fun getHostVersion(
        @Header("vmware-api-session-id") sessionId: String
    ): Response<Any>

    // ==================== 虚拟机 (Virtual Machines) ====================
    // VMware vSphere 7/8 提供的基础虚拟机查询接口
    @GET("api/vcenter/vm")
    suspend fun getVmList(
        @Header("vmware-api-session-id") sessionId: String
    ): Response<VmListResponse>
}