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
}
