package dev.jev.wechat;

import android.util.Log;

/** 统一日志：微信进程内用 logcat，tag=JevForWechat，方便在 LSPatch 日志里过滤。 */
final class JevLog {
    private static final String TAG = "JevForWechat";

    static void i(String msg) {
        Log.i(TAG, msg);
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    static void e(String msg) {
        Log.e(TAG, msg);
    }

    private JevLog() {
    }
}
