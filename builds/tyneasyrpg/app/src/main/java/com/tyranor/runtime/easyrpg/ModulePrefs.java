package com.tyranor.runtime.easyrpg;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

/**
 * Consumes the TYN per-game SETTINGS payload
 * (extra com.tyranor.runtime.extra.SETTINGS, format documented by TYN).
 * Values handed over by TYN take priority over local defaults; without the
 * extra the declared defaults are used.
 */
public final class ModulePrefs {
    private static final String TAG = "TYN-EasyRPG[Prefs]";
    private static final String PREFS_FILE = "tyn_module_prefs";

    private static final String KEY_PAD_ENABLED = "padEnabled";
    private static final String KEY_CHEATS_ENABLED = "cheatsEnabled";
    private static final String KEY_ENCODING = "encoding";
    private static final String KEY_RTP_DIRECTORY = "rtpDirectory";
    private static final String KEY_SAVE_DIRECTORY = "saveDirectory";
    private static final String KEY_TEST_PLAY = "testPlay";
    private static final String KEY_NO_AUDIO = "noAudio";

    private ModulePrefs() {}

    /** Applies the SETTINGS extra; ignores broken payloads entirely. */
    public static void applySettingsExtra(Context context, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            JSONObject payload = new JSONObject(raw);
            if (payload.optInt("version", -1) != 1) return;
            JSONObject values = payload.optJSONObject("values");
            if (values == null) return;
            SharedPreferences.Editor editor = prefs(context).edit();
            if (values.has(KEY_PAD_ENABLED)) {
                editor.putBoolean(KEY_PAD_ENABLED, values.optBoolean(KEY_PAD_ENABLED, true));
            }
            if (values.has(KEY_CHEATS_ENABLED)) {
                editor.putBoolean(KEY_CHEATS_ENABLED, values.optBoolean(KEY_CHEATS_ENABLED, true));
            }
            if (values.has(KEY_ENCODING)) {
                editor.putString(KEY_ENCODING, values.optString(KEY_ENCODING, ""));
            }
            if (values.has(KEY_RTP_DIRECTORY)) {
                editor.putString(KEY_RTP_DIRECTORY, values.optString(KEY_RTP_DIRECTORY, ""));
            }
            if (values.has(KEY_SAVE_DIRECTORY)) {
                editor.putString(KEY_SAVE_DIRECTORY, values.optString(KEY_SAVE_DIRECTORY, ""));
            }
            if (values.has(KEY_TEST_PLAY)) {
                editor.putBoolean(KEY_TEST_PLAY, values.optBoolean(KEY_TEST_PLAY, false));
            }
            if (values.has(KEY_NO_AUDIO)) {
                editor.putBoolean(KEY_NO_AUDIO, values.optBoolean(KEY_NO_AUDIO, false));
            }
            editor.apply();
        } catch (Exception error) {
            Log.w(TAG, "Ignoring malformed SETTINGS payload", error);
        }
    }

    public static boolean isPadEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PAD_ENABLED, true);
    }

    public static boolean isCheatsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CHEATS_ENABLED, true);
    }

    public static String getEncoding(Context context) {
        return prefs(context).getString(KEY_ENCODING, "");
    }

    public static String getRtpDirectory(Context context) {
        return prefs(context).getString(KEY_RTP_DIRECTORY, "").trim();
    }

    public static String getSaveDirectory(Context context) {
        return prefs(context).getString(KEY_SAVE_DIRECTORY, "").trim();
    }

    public static boolean isTestPlayEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TEST_PLAY, false);
    }

    public static boolean isAudioDisabled(Context context) {
        return prefs(context).getBoolean(KEY_NO_AUDIO, false);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }
}
