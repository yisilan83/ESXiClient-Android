package dev.esxiclient.app.network

import okhttp3.Response

interface EsxiApiService {
    suspend fun executeSoap(
        url: String,
        soapXml: String
    ): Response
}