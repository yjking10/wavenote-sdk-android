package cn.wavenote.demo

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 示例 HTTP 契约，日志不包含请求/响应原文，不持久化凭据。 */
class IdentityHTTP(baseURL: String, private val transport: Transport = NetworkTransport()) {
    data class Request(val url: String, val headers: Map<String, String>, val body: String)
    data class Reply(val status: Int?, val body: String?, val errorCode: Int? = null)
    data class Result(val ownership: String? = null, val code: Int? = null)
    fun interface Transport { fun send(request: Request, done: (Reply) -> Unit): () -> Unit }
    private val base = URI(baseURL).also {
        require(it.scheme == "https" && it.host != null && it.userInfo == null && it.query == null && it.fragment == null)
    }.toString().trimEnd('/')
    private data class Pending(var cancel: () -> Unit, val done: (Result) -> Unit)
    private val pending = mutableMapOf<UUID, Pending>()
    /** 每次调用生成新的逻辑操作和幂等键；本示例不自动重试。 */
    fun request(action: String, serial: String, user: String, token: String, done: (Result) -> Unit) {
        if (action !in listOf("check", "bind", "unbind") || serial.isEmpty() || user.isEmpty() || token.isEmpty() || token.any { it == '\r' || it == '\n' }) {
            done(Result(code = 1201)); return
        }
        val headers = mutableMapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json", "Accept" to "application/json")
        if (action != "check") headers["Idempotency-Key"] = UUID.randomUUID().toString()
        val request = Request("$base/v1/device-bindings/$action", headers, JSONObject().put("serialNumber", serial).put("userIdentifier", user).toString())
        val id = UUID.randomUUID()
        synchronized(pending) { pending[id] = Pending({}, done) }
        val cancel = transport.send(request) { reply ->
            val entry = synchronized(pending) { pending.remove(id) }
            entry?.done?.invoke(if (reply.errorCode != null) Result(code = reply.errorCode) else parse(action, reply))
        }
        val finished = synchronized(pending) { pending[id]?.let { it.cancel = cancel; false } ?: true }
        if (finished) cancel()
    }
    fun cancelAll() {
        val entries = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        entries.forEach { it.cancel(); it.done(Result(code = 1017)) }
    }
    companion object {
        fun parse(action: String, reply: Reply): Result {
            return runCatching {
                val obj = JSONObject(reply.body ?: error("empty"))
                require(obj.opt("code") is String && obj.opt("message") is String && obj.opt("requestId") is String && obj.getString("requestId").isNotEmpty())
                val code = obj.getString("code")
                if (reply.status == 200 && code == "OK") {
                    val data = obj.getJSONObject("data"); require(data.opt("ownership") is String)
                    val owner = data.getString("ownership")
                    require(owner in listOf("UNBOUND", "CURRENT_USER", "ANOTHER_USER"))
                    require(action == "check" || (action == "bind" && owner == "CURRENT_USER") || (action == "unbind" && owner == "UNBOUND"))
                    Result(owner)
                } else {
                    val errors = mapOf("INVALID_ARGUMENT" to (400 to 1201), "INVALID_CREDENTIAL" to (401 to 1012),
                        "USER_IDENTITY_MISMATCH" to (403 to 1015), "BINDING_REJECTED" to (403 to 1007),
                        "DEVICE_NOT_OWNED" to (403 to 1009), "UNBIND_REJECTED" to (403 to 1009),
                        "DEVICE_BOUND_TO_ANOTHER_USER" to (409 to 1004), "IDEMPOTENCY_KEY_REUSED" to (409 to 1201),
                        "REQUEST_IN_PROGRESS" to (409 to 1106), "PAYLOAD_TOO_LARGE" to (413 to 1201),
                        "UNSUPPORTED_MEDIA_TYPE" to (415 to 1201), "RATE_LIMITED" to (429 to 1106),
                        "INTERNAL_ERROR" to (500 to 1206), "SERVICE_UNAVAILABLE" to (503 to 1206))
                    val mapped = errors[code] ?: error("unknown")
                    require(obj.has("data") && obj.isNull("data") && reply.status == mapped.first)
                    Result(code = mapped.second)
                }
            }.getOrElse { Result(code = 1103) }
        }
    }
}
/** 无重定向、无缓存；总期限 10 秒独立于 connect/read timeout，避免分阶段累计超时。 */
private class NetworkTransport : IdentityHTTP.Transport {
    companion object {
        private val workers = Executors.newFixedThreadPool(2)
        private val deadlines = Executors.newSingleThreadScheduledExecutor()
    }
    override fun send(request: IdentityHTTP.Request, done: (IdentityHTTP.Reply) -> Unit): () -> Unit {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(reply: IdentityHTTP.Reply) { if (finished.compareAndSet(false, true)) done(reply) }
        val timeout = deadlines.schedule({ finish(IdentityHTTP.Reply(null, null, 1018)); connection.disconnect() }, 10, TimeUnit.SECONDS)
        val task = workers.submit {
            try {
                connection.requestMethod = "POST"; connection.instanceFollowRedirects = false; connection.useCaches = false
                connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.doOutput = true
                request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                // 绑定响应很小，限制恶意或错误网关响应的内存占用。
                val bytes = stream?.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (output.size() <= 65536) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                finish(if (bytes != null && bytes.size > 65536) IdentityHTTP.Reply(null, null, 1103)
                    else IdentityHTTP.Reply(status, bytes?.toString(Charsets.UTF_8)))
            } catch (error: Exception) { finish(IdentityHTTP.Reply(null, null, if (error is SocketTimeoutException) 1018 else 1206)) }
            finally { timeout.cancel(false); connection.disconnect() }
        }
        return { finished.set(true); timeout.cancel(false); task.cancel(true); connection.disconnect() }
    }
}
