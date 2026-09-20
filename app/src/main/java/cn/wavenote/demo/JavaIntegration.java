package cn.wavenote.demo;
import android.content.Context;
import cn.wavenote.sdk.*;
/** 可复制的 Java 接入代码；凭据仅由 Provider 管理，不自动扫描或录音。 */
public final class JavaIntegration {
    public static WaveNoteSDK configure(Context context, String user, WaveNoteIdentityProvider provider, WaveNoteSDKDelegate callback) {
        WaveNoteSDK sdk = WaveNoteSDK.getInstance(context);
        sdk.setDelegate(callback);
        sdk.configure(new WaveNoteSDKConfiguration(user, false, WaveNoteReconnectPolicy.NONE, provider));
        return sdk;
    }
    public static void clearForLogout(WaveNoteSDK sdk) { sdk.clearConfiguration(); }
    public static void battery(WaveNoteSDK sdk, WaveNoteCompletion<WaveNoteSettingsSnapshot> completion) {
        sdk.getDeviceSettings().query(WaveNoteSetting.BATTERY, completion);
    }
    public static void localAudio(WaveNoteSDK sdk, String sn, WaveNoteRecordMode mode, String name, WaveNoteCompletion<WaveNoteLocalAudio> completion) {
        sdk.getFiles().findLocalAudio(sn, mode, name, completion);
    }
    /** 仅删除 SDK 托管的本地音频和续传数据，不删除设备文件。 */
    public static void deleteLocalAudio(WaveNoteSDK sdk, String sn, WaveNoteRecordMode mode, String name, WaveNoteControlCompletion completion) {
        sdk.getFiles().deleteLocalAudio(sn, mode, name, completion);
    }
    /** deleteSource=false 保留设备文件；true 时本地完成后请求删除，删除失败会保留本地音频。 */
    public static WaveNoteOperation download(WaveNoteSDK sdk, WaveNoteFile file, boolean resume, boolean deleteSource, WaveNoteCompletion<WaveNoteLocalAudio> completion) {
        return sdk.getFiles().downloadToStorage(file, WaveNoteTransferTransport.BLUETOOTH, resume, deleteSource, completion);
    }
}
