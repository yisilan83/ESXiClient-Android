package dev.esxiclient.app.network

import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface EsxiApiService {
    
    // ==================== 认证 (Auth) ====================
    // 使用 Basic Auth 获取 ESXi Session ID
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
    @GET("api/vcenter/vm")
    suspend fun getVmList(
        @Header("vmware-api-session-id") sessionId: String
    ): Response<Any>
}