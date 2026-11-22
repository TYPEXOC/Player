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

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }
}
