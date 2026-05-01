package dev.esxiclient.app.network.dto

import org.simpleframework.xml.Element
import org.simpleframework.xml.Namespace
import org.simpleframework.xml.NamespaceList
import org.simpleframework.xml.Root

@Root(name = "soapenv:Envelope")
@NamespaceList(
    Namespace(prefix = "soapenv", reference = "http://schemas.xmlsoap.org/soap/envelope/"),
    Namespace(prefix = "urn", reference = "urn:vim25")
)
data class SoapEnvelope(
    @field:Element(name = "Body")
    var body: SoapBody? = null
)

data class SoapBody(
    @field:Element(name = "Login", required = false)
    var login: LoginRequest? = null,

    @field:Element(name = "LoginResponse", required = false)
    var loginResponse: LoginResponse? = null
)

data class LoginRequest(
    @field:Element(name = "_this")
    var _this: MoRef = MoRef("SessionManager", "ha-sessionmgr"),

    @field:Element(name = "userName")
    var userName: String = "",

    @field:Element(name = "password")
    var password: String = ""
)

data class MoRef(
    @field:org.simpleframework.xml.Attribute(name = "type")
    var type: String = "",

    @field:org.simpleframework.xml.Text
    var value: String = ""
)

data class LoginResponse(
    @field:Element(name = "returnval")
    var returnVal: UserSession? = null
)

data class UserSession(
    @field:Element(name = "key")
    var key: String = "",

    @field:Element(name = "userName")
    var userName: String = ""
)
