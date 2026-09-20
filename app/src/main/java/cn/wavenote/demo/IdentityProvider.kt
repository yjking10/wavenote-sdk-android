package cn.wavenote.demo
import cn.wavenote.sdk.*

/** 可复制的宿主 Provider；凭据从宿主登录会话快照取得，http == null 是仅进程内存模拟。 */
class HTTPIdentityProvider(val http: IdentityHTTP?, private val credential: () -> String? = { null }) : WaveNoteIdentityProvider {
    private val owners = mutableMapOf<String, String>()
    override fun checkOwnership(serialNumber: String, userIdentifier: String, completion: WaveNoteCompletion<WaveNoteOwnership>) {
        if (http == null) {
            completion.complete(when (owners[serialNumber]) { null -> WaveNoteOwnership.UNBOUND; userIdentifier -> WaveNoteOwnership.CURRENT_USER; else -> WaveNoteOwnership.ANOTHER_USER }, null)
        } else request("check", serialNumber, userIdentifier) { result ->
            completion.complete(result.ownership?.let { WaveNoteOwnership.valueOf(it) }, result.code?.let { error(it, "check") })
        }
    }
    override fun bind(serialNumber: String, userIdentifier: String, completion: WaveNoteControlCompletion) = change("bind", serialNumber, userIdentifier, completion)
    override fun unbind(serialNumber: String, userIdentifier: String, completion: WaveNoteControlCompletion) = change("unbind", serialNumber, userIdentifier, completion)
    override fun fetchDeviceSignature(serialNumber: String, userIdentifier: String, completion: WaveNoteCompletion<WaveNoteDeviceSignature>) =
        completion.complete(null, WaveNoteError(WaveNoteErrorCode.IDENTITY_PROVIDER_UNAVAILABLE, "deviceSignature"))
    override fun fetchUserKeyPair(userIdentifier: String, completion: WaveNoteCompletion<WaveNoteUserKeyPair>) =
        completion.complete(null, WaveNoteError(WaveNoteErrorCode.IDENTITY_PROVIDER_UNAVAILABLE, "userKeyPair"))
    private fun change(action: String, serial: String, user: String, done: WaveNoteControlCompletion) {
        if (http != null) request(action, serial, user) { result -> done.complete(result.code?.let { error(it, action) }) }
        else {
            if (owners[serial] != null && owners[serial] != user) { done.complete(error(if (action == "bind") 1004 else 1009, action)); return }
            if (action == "bind") owners[serial] = user else owners.remove(serial)
            done.complete(null)
        }
    }
    private fun request(action: String, serial: String, user: String, done: (IdentityHTTP.Result) -> Unit) {
        val token = credential()
        if (token.isNullOrBlank()) done(IdentityHTTP.Result(code = 1012)) else http!!.request(action, serial, user, token, done)
    }
    private fun error(code: Int, action: String) = WaveNoteError(WaveNoteErrorCode.entries.first { it.value == code }, "server.$action")
}
