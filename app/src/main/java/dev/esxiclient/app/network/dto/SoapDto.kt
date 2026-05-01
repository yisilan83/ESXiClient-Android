package dev.esxiclient.app.network.dto

import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Namespace
import org.simpleframework.xml.NamespaceList
import org.simpleframework.xml.Root

@Root(name = "soapenv:Envelope")
@NamespaceList(
    Namespace(prefix = "soapenv", reference = "http://schemas.xmlsoap.org/soap/envelope/"),
    Namespace(prefix = "urn", reference = "urn:vim25"),
    Namespace(prefix = "xsi", reference = "http://www.w3.org/2001/XMLSchema-instance")
)
data class SoapEnvelope(
    @field:Element(name = "Body")
    var body: SoapBody? = null
)

data class SoapBody(
    @field:Element(name = "Login", required = false)
    var login: LoginRequest? = null,

    @field:Element(name = "LoginResponse", required = false)
    var loginResponse: LoginResponse? = null,

    @field:Element(name = "RetrieveServiceContent", required = false)
    var retrieveServiceContent: RetrieveServiceContentRequest? = null,

    @field:Element(name = "RetrieveServiceContentResponse", required = false)
    var retrieveServiceContentResponse: RetrieveServiceContentResponse? = null,

    @field:Element(name = "RetrievePropertiesEx", required = false)
    var retrievePropertiesEx: RetrievePropertiesExRequest? = null,

    @field:Element(name = "RetrievePropertiesExResponse", required = false)
    var retrievePropertiesExResponse: RetrievePropertiesExResponse? = null
)

// --- Login ---
data class LoginRequest(
    @field:Element(name = "_this")
    var _this: MoRef = MoRef("SessionManager", "ha-sessionmgr"),
    @field:Element(name = "userName")
    var userName: String = "",
    @field:Element(name = "password")
    var password: String = ""
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

// --- RetrieveServiceContent (for Version) ---
data class RetrieveServiceContentRequest(
    @field:Element(name = "_this")
    var _this: MoRef = MoRef("ServiceInstance", "ServiceInstance")
)

data class RetrieveServiceContentResponse(
    @field:Element(name = "returnval")
    var returnVal: ServiceContent? = null
)

data class ServiceContent(
    @field:Element(name = "about")
    var about: AboutInfo? = null,
    @field:Element(name = "propertyCollector")
    var propertyCollector: MoRef? = null,
    @field:Element(name = "rootFolder")
    var rootFolder: MoRef? = null
)

data class AboutInfo(
    @field:Element(name = "fullName")
    var fullName: String = "",
    @field:Element(name = "version")
    var version: String = ""
)

// --- RetrievePropertiesEx (for VM List) ---
data class RetrievePropertiesExRequest(
    @field:Element(name = "_this")
    var _this: MoRef? = null,
    @field:Element(name = "specSet")
    var specSet: PropertyFilterSpec? = null,
    @field:Element(name = "options")
    var options: RetrieveOptions = RetrieveOptions()
)

data class PropertyFilterSpec(
    @field:Element(name = "propSet")
    var propSet: PropertySpec? = null,
    @field:Element(name = "objectSet")
    var objectSet: ObjectSpec? = null
)

data class PropertySpec(
    @field:Element(name = "type")
    var type: String = "VirtualMachine",
    @field:ElementList(entry = "pathSet", inline = true)
    var pathSet: List<String> = listOf("name", "summary.runtime.powerState", "config.hardware.numCPU", "config.hardware.memoryMB")
)

data class ObjectSpec(
    @field:Element(name = "obj")
    var obj: MoRef? = null,
    @field:Element(name = "skip")
    var skip: Boolean = false,
    @field:ElementList(entry = "selectSet", inline = true, required = false)
    var selectSet: List<TraversalSpec>? = null
)

data class TraversalSpec(
    @field:org.simpleframework.xml.Attribute(name = "type", required = false)
    var xsiType: String = "TraversalSpec",
    @field:Element(name = "name", required = false)
    var name: String? = null,
    @field:Element(name = "type")
    var type: String = "",
    @field:Element(name = "path")
    var path: String = "",
    @field:Element(name = "skip")
    var skip: Boolean = false,
    @field:ElementList(entry = "selectSet", inline = true, required = false)
    var selectSet: List<SelectionSpec>? = null
)

data class SelectionSpec(
    @field:Element(name = "name")
    var name: String = ""
)

data class RetrieveOptions(
    @field:Element(name = "maxObjects", required = false)
    var maxObjects: Int? = null
)

data class RetrievePropertiesExResponse(
    @field:Element(name = "returnval")
    var returnVal: RetrieveResult? = null
)

data class RetrieveResult(
    @field:ElementList(entry = "objects", inline = true, required = false)
    var objects: List<ObjectContent>? = null,
    @field:Element(name = "token", required = false)
    var token: String? = null
)

data class ObjectContent(
    @field:Element(name = "obj")
    var obj: MoRef? = null,
    @field:ElementList(entry = "propSet", inline = true, required = false)
    var propSet: List<DynamicProperty>? = null
)

data class DynamicProperty(
    @field:Element(name = "name")
    var name: String = "",
    @field:Element(name = "val")
    var value: Any? = null // SimpleXML might need help here, but let's try
)

// --- Common ---
data class MoRef(
    @field:org.simpleframework.xml.Attribute(name = "type")
    var type: String = "",
    @field:org.simpleframework.xml.Text
    var value: String = ""
)
