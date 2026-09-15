package cn.wavenote.demo
import cn.wavenote.sdk.*

/** 可复制的宿主 Provider；http == null 是仅进程内存模拟，不是真实鉴权。 */
class HTTPIdentityProvider(val http: IdentityHTTP?) : WaveNoteIdentityProvider {
    private val owners = mutableMapOf<String, String>()
    override fun checkOwnership(serialNumber: String, apiKey: String, userIdentifier: String, completion: WaveNoteCompletion<WaveNoteOwnership>) {
        if (http == null) {
            completion.complete(when (owners[serialNumber]) { null -> WaveNoteOwnership.UNBOUND; userIdentifier -> WaveNoteOwnership.CURRENT_USER; else -> WaveNoteOwnership.ANOTHER_USER }, null)
        } else http.request("check", serialNumber, userIdentifier, apiKey) { result ->
            completion.complete(result.ownership?.let { WaveNoteOwnership.valueOf(it) }, result.code?.let { error(it, "check") })
        }
    }
    override fun bind(serialNumber: String, apiKey: String, userIdentifier: String, completion: WaveNoteControlCompletion) = change("bind", serialNumber, apiKey, userIdentifier, completion)
    override fun unbind(serialNumber: String, apiKey: String, userIdentifier: String, completion: WaveNoteControlCompletion) = change("unbind", serialNumber, apiKey, userIdentifier, completion)
    private fun change(action: String, serial: String, token: String, user: String, done: WaveNoteControlCompletion) {
        if (http != null) http.request(action, serial, user, token) { result -> done.complete(result.code?.let { error(it, action) }) }
        else {
            if (owners[serial] != null && owners[serial] != user) { done.complete(error(if (action == "bind") 1004 else 1009, action)); return }
            if (action == "bind") owners[serial] = user else owners.remove(serial)
            done.complete(null)
        }
    }
    private fun error(code: Int, action: String) = WaveNoteError(WaveNoteErrorCode.entries.first { it.value == code }, "server.$action")
}
