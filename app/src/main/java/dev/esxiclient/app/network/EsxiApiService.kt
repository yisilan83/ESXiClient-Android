package dev.esxiclient.app.network

import okhttp3.Response

interface EsxiApiService {
    suspend fun executeSoap(
        url: String,
        soapXml: String,
        sessionId: String?,
        apiVersion: String = "8.0"
    ): Response

    /**
     * New: Execute REST API GET request with Basic Auth.
     * Used to bypass ESXi 8.0 System.Read permission restrictions.
     */
    suspend fun executeRest(
        url: String,
        path: String,
        username: String,
        password: String
    ): Response
}
