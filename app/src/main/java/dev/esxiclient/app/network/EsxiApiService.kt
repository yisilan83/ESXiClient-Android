package dev.esxiclient.app.network

import okhttp3.Response

interface EsxiApiService {
    suspend fun executeSoap(
        url: String,
        soapXml: String,
        sessionId: String?,
        apiVersion: String = "8.0"
    ): Response
}
