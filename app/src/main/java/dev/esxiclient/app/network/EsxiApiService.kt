package dev.esxiclient.app.network

import dev.esxiclient.app.network.dto.SoapEnvelope
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface EsxiApiService {
    
    @Headers(
        "Content-Type: text/xml; charset=utf-8",
        "SOAPAction: \"urn:vim25/6.7\""
    )
    @POST("sdk")
    suspend fun soapRequest(
        @Body envelope: SoapEnvelope
    ): Response<SoapEnvelope>
}
