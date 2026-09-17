package cn.wavenote.demo;
import android.content.Context;
import cn.wavenote.sdk.*;
/** 可复制的 Java 接入代码；凭据由调用方在运行时传入，不自动扫描或录音。 */
public final class JavaIntegration {
    public static WaveNoteSDK configure(Context context, String token, String user, WaveNoteIdentityProvider provider, WaveNoteSDKDelegate callback) {
        WaveNoteSDK sdk = WaveNoteSDK.getInstance(context);
        sdk.setDelegate(callback);
        sdk.configure(new WaveNoteSDKConfiguration(token, user, false, WaveNoteReconnectPolicy.NONE, provider));
        return sdk;
    }
    public static void battery(WaveNoteSDK sdk, WaveNoteCompletion<WaveNoteSettingsSnapshot> completion) {
        sdk.getDeviceSettings().query(WaveNoteSetting.BATTERY, completion);
    }
    public static void localAudio(WaveNoteSDK sdk, String sn, WaveNoteRecordMode mode, String name, WaveNoteCompletion<WaveNoteLocalAudio> completion) {
        sdk.getFiles().findLocalAudio(sn, mode, name, completion);
    }
    public static WaveNoteOperation download(WaveNoteSDK sdk, WaveNoteFile file, boolean resume, WaveNoteCompletion<WaveNoteLocalAudio> completion) {
        return sdk.getFiles().downloadToStorage(file, WaveNoteTransferTransport.BLUETOOTH, resume, completion);
    }
}
