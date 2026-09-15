package cn.wavenote.demo
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class IdentityHTTPTest {
    private fun body(owner: String = "CURRENT_USER") = """{"code":"OK","message":"成功","requestId":"synthetic","data":{"ownership":"$owner"}}"""
    @Test fun encodingAllActionsAndExactlyOnce() {
        val requests = mutableListOf<IdentityHTTP.Request>(); val replies = mutableListOf<(IdentityHTTP.Reply)->Unit>()
        val client = IdentityHTTP("https://sandbox.invalid", IdentityHTTP.Transport { request,done -> requests.add(request);replies.add(done); {} })
        val values = mutableListOf<String?>()
        listOf("check","bind","unbind").forEach { client.request(it,"synthetic-sn","synthetic-user","synthetic-token") { result -> values.add(result.ownership) } }
        assertNull(requests[0].headers["Idempotency-Key"])
        assertNotEquals(requests[1].headers["Idempotency-Key"],requests[2].headers["Idempotency-Key"])
        assertEquals("Bearer synthetic-token",requests[1].headers["Authorization"])
        assertEquals("synthetic-user",JSONObject(requests[1].body).getString("userIdentifier"))
        replies[0](IdentityHTTP.Reply(200,body("ANOTHER_USER"))); replies[1](IdentityHTTP.Reply(200,body())); replies[2](IdentityHTTP.Reply(200,body("UNBOUND"))); replies[2](IdentityHTTP.Reply(200,body()))
        assertEquals(listOf("ANOTHER_USER","CURRENT_USER","UNBOUND"),values)
    }
    @Test fun cancelAccountSwitchAndLateReply() {
        val replies = mutableListOf<(IdentityHTTP.Reply)->Unit>(); val codes = mutableListOf<Int?>(); var cancelled=0
        val client = IdentityHTTP("https://sandbox.invalid", IdentityHTTP.Transport { _,done -> replies.add(done); { cancelled++ } })
        client.request("bind","synthetic","a","a") { codes.add(it.code) }; client.cancelAll()
        client.request("bind","synthetic","b","b") { codes.add(it.code) }
        replies[0](IdentityHTTP.Reply(200,body()));assertEquals(listOf(1017),codes)
        replies[1](IdentityHTTP.Reply(200,body()));assertEquals(listOf(1017,null),codes);assertEquals(1,cancelled)
    }
    @Test fun strictBusinessErrorsAndMalformedResponses() {
        val cases = listOf(Triple(400,"INVALID_ARGUMENT",1201),Triple(401,"INVALID_CREDENTIAL",1012),Triple(403,"USER_IDENTITY_MISMATCH",1015),Triple(403,"BINDING_REJECTED",1007),Triple(403,"DEVICE_NOT_OWNED",1009),Triple(403,"UNBIND_REJECTED",1009),Triple(409,"DEVICE_BOUND_TO_ANOTHER_USER",1004),Triple(409,"IDEMPOTENCY_KEY_REUSED",1201),Triple(409,"REQUEST_IN_PROGRESS",1106),Triple(413,"PAYLOAD_TOO_LARGE",1201),Triple(415,"UNSUPPORTED_MEDIA_TYPE",1201),Triple(429,"RATE_LIMITED",1106),Triple(500,"INTERNAL_ERROR",1206),Triple(503,"SERVICE_UNAVAILABLE",1206))
        cases.forEach { (status,code,expected) ->
            val body="""{"code":"$code","message":"x","requestId":"trace","data":null}"""
            assertEquals(expected,IdentityHTTP.parse("bind",IdentityHTTP.Reply(status,body)).code)
            assertEquals(1103,IdentityHTTP.parse("bind",IdentityHTTP.Reply(200,body)).code)
        }
        listOf("", "<html>error</html>", "{}",body("FUTURE"),body("UNBOUND")).forEach { assertEquals(1103,IdentityHTTP.parse("bind",IdentityHTTP.Reply(200,it)).code) }
        assertEquals(1103,IdentityHTTP.parse("check",IdentityHTTP.Reply(500,body())).code)
    }
    @Test fun timeoutAndTransportFailureDoNotRetry() {
        listOf(1018,1206).forEach { code ->
            var calls=0;var actual:Int?=null
            val client=IdentityHTTP("https://sandbox.invalid",IdentityHTTP.Transport { _,done -> calls++;done(IdentityHTTP.Reply(null,null,code)); {} })
            client.request("check","synthetic","synthetic","synthetic") { actual=it.code }
            assertEquals(code,actual);assertEquals(1,calls)
        }
    }
    @Test fun rejectUnsafeConfiguration() {
        listOf("http://host","https://token@host","https://host?token=x","https://host#fragment").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) { IdentityHTTP(address) }
        }
        val client=IdentityHTTP("https://sandbox.invalid",IdentityHTTP.Transport { _,_ -> fail("must not send"); {} })
        client.request("check","s","u","t\r\nheader") { assertEquals(1201,it.code) }
    }
}
