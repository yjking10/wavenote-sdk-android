package cn.wavenote.demo
import android.content.Context
import cn.wavenote.sdk.*

/** 宿主持有 provider/delegate，所有调用在主线程。Token 从宿主登录系统取得。 */
object KotlinIntegration {
    fun configure(context: Context, token: String, user: String, provider: WaveNoteIdentityProvider, delegate: WaveNoteSDKDelegate): WaveNoteSDK {
        return WaveNoteSDK.getInstance(context).also {
            it.delegate = delegate
            it.configure(WaveNoteSDKConfiguration(token, user, enableAutoReconnect = false, identityProvider = provider))
        }
    }
    fun battery(sdk: WaveNoteSDK, completion: WaveNoteCompletion<WaveNoteSettingsSnapshot>) {
        sdk.deviceSettings.query(WaveNoteSetting.BATTERY, completion)
    }
    /** 查询当前用户指定设备的本地音频；不要求连接，无匹配为 null/null。 */
    fun localAudio(sdk: WaveNoteSDK, sn: String, mode: WaveNoteRecordMode, name: String, completion: WaveNoteCompletion<WaveNoteLocalAudio>) =
        sdk.files.findLocalAudio(sn, mode, name, completion)
    /** 仅删除 SDK 托管的本地音频和续传数据，不删除设备文件。 */
    fun deleteLocalAudio(sdk: WaveNoteSDK, sn: String, mode: WaveNoteRecordMode, name: String, completion: WaveNoteControlCompletion) =
        sdk.files.deleteLocalAudio(sn, mode, name, completion)
    /** 默认保留设备文件；删除未确认时返回错误，本地已完成音频仍保留。 */
    fun download(sdk: WaveNoteSDK, file: WaveNoteFile, resume: Boolean, deleteSource: Boolean = false, completion: WaveNoteCompletion<WaveNoteLocalAudio>): WaveNoteOperation =
        sdk.files.downloadToStorage(file, resume = resume, deleteSource = deleteSource, completion = completion)
}
