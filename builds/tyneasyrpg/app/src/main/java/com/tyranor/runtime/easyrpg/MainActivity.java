package com.tyranor.runtime.easyrpg;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import com.tyranor.runtime.diagnostics.TynRuntimeDiagnostics;
import org.easyrpg.player.game_browser.GameBrowserActivity;
import org.easyrpg.player.R;

import org.easyrpg.player.player.EasyRpgPlayerActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TYN runtime module entry: receives the launch hand-off, prepares the
 * translation catalog and starts the EasyRPG engine activity. The launcher
 * follows EasyRPG's upstream startup flow.
 */
public class MainActivity extends Activity {
    private static final String TAG = "TYN-EasyRPG";

    private static final String RUNTIME_MODULE_ACTION_LAUNCH = "com.tyranor.runtime.action.LAUNCH";
    private static final String EXTRA_FOLDER_PATH = "com.tyranor.runtime.extra.FOLDER_PATH";
    private static final String EXTRA_GAME_TITLE = "com.tyranor.runtime.extra.GAME_TITLE";
    private static final String EXTRA_PROTOCOL_VERSION = "com.tyranor.runtime.extra.PROTOCOL_VERSION";
    private static final String EXTRA_SETTINGS = "com.tyranor.runtime.extra.SETTINGS";
    private static final String EXTRA_TRANSLATION_MAP_URI = "com.tyranor.runtime.extra.TRANSLATION_MAP_URI";
    private static final int REQUEST_MANAGE_STORAGE = 4101;
    private static final int REQUEST_READ_STORAGE = 4102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (!RUNTIME_MODULE_ACTION_LAUNCH.equals(intent.getAction())) {
            // Fall through to EasyRPG's upstream game browser.
            startActivity(new Intent(this, GameBrowserActivity.class));
            finish();
            return;
        }

        long requestedAtMillis = System.currentTimeMillis();
        long requestedAtElapsed = SystemClock.elapsedRealtime();

        String folder = intent.getStringExtra(EXTRA_FOLDER_PATH);
        if (folder == null || folder.trim().isEmpty()) {
            fail(R.string.tyn_launch_missing_folder);
            return;
        }
        File gameDir = new File(folder);
        if (!gameDir.isDirectory() || !gameDir.canRead()) {
            fail(R.string.tyn_launch_folder_invalid);
            return;
        }
        if (!hasStorageAccess()) {
            requestStorageAccess();
            return;
        }

        String gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE);
        int protocol = intent.getIntExtra(EXTRA_PROTOCOL_VERSION, 1);
        ModulePrefs.applySettingsExtra(this, intent.getStringExtra(EXTRA_SETTINGS));
        String mapUriRaw = intent.getStringExtra(EXTRA_TRANSLATION_MAP_URI);
        Uri mapUri = mapUriRaw == null || mapUriRaw.trim().isEmpty() ? null : Uri.parse(mapUriRaw);

        beginDiagnostics(gameDir, gameTitle, mapUri != null, requestedAtMillis, requestedAtElapsed);
        TynRuntimeDiagnostics.append(TynSession.current(),
            "Hand-off received: protocol=" + protocol + " folder=" + folder);

        // Translation loads in memory: the session converts the map and the
        // engine activity pushes it via JNI once its libraries are up.
        EasyRpgTranslationSession session = EasyRpgTranslationSession.get(this);
        session.beginSession(getContentResolver(), mapUri);

        EasyRpgPlayerActivity.tynHidePad = !ModulePrefs.isPadEnabled(this);

        List<String> args = new ArrayList<>();
        args.add(gameDir.getAbsolutePath());
        if (ModulePrefs.isCheatsEnabled(this)) {
            // Same directory the shared cheat controller (Kotlin side) uses.
            args.add("--tyneasyrpg-cheat-dir");
            args.add(getCacheDir().getAbsolutePath());
        }
        String encoding = ModulePrefs.getEncoding(this);
        if (!encoding.trim().isEmpty()) {
            args.add("--encoding");
            args.add(encoding.trim());
        }
        String rtpDirectory = ModulePrefs.getRtpDirectory(this);
        if (!rtpDirectory.isEmpty()) {
            args.add("--rtp-path");
            args.add(rtpDirectory);
        }
        String saveDirectory = ModulePrefs.getSaveDirectory(this);
        if (!saveDirectory.isEmpty()) {
            args.add("--save-path");
            args.add(saveDirectory);
        }
        if (ModulePrefs.isTestPlayEnabled(this)) {
            args.add("--test-play");
        }
        if (ModulePrefs.isAudioDisabled(this)) {
            args.add("--no-audio");
        }

        Intent engine = new Intent(this, EasyRpgPlayerActivity.class);
        engine.putExtra(EasyRpgPlayerActivity.TAG_COMMAND_LINE, args.toArray(new String[0]));
        engine.putExtra(EasyRpgPlayerActivity.TAG_SAVE_PATH,
            saveDirectory.isEmpty() ? gameDir.getAbsolutePath() : saveDirectory);
        startActivity(engine);
        TynRuntimeDiagnostics.append(TynSession.current(),
            "Engine activity started with " + args.size() + " argument(s), translation loading in background");
        finish();
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent settingsIntent = new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(settingsIntent, REQUEST_MANAGE_STORAGE);
            } catch (ActivityNotFoundException error) {
                startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    REQUEST_MANAGE_STORAGE);
            }
        } else {
            requestPermissions(
                new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE },
                REQUEST_READ_STORAGE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            continueAfterStorageRequest();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_READ_STORAGE) {
            continueAfterStorageRequest();
        }
    }

    private void continueAfterStorageRequest() {
        if (hasStorageAccess()) {
            recreate();
        } else {
            Toast.makeText(this, R.string.tyn_launch_no_storage, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void beginDiagnostics(File gameDir, String gameTitle, boolean translationSelected,
                                  long requestedAtMillis, long requestedAtElapsed) {
        try {
            File sessionDir = createSessionDirectory(gameDir);
            TynSession.setCurrent(sessionDir);
            TynRuntimeDiagnostics.beginSession(this, sessionDir, gameTitle, gameDir,
                0, 0, translationSelected, ModulePrefs.isPadEnabled(this),
                "engine=EasyRPG Player", requestedAtMillis, requestedAtElapsed);
        } catch (Exception error) {
            TynSession.setCurrent(null);
            TynRuntimeDiagnostics.append(null, "Could not create diagnostics session", error);
        }
    }

    /** Session layout matches the shared @xml/diagnostics_paths cache path. */
    private File createSessionDirectory(File gameDir) {
        String identity;
        try {
            identity = gameDir.getCanonicalPath();
        } catch (Exception error) {
            identity = gameDir.getAbsolutePath();
        }
        String visible = identity.replaceAll("[^A-Za-z0-9._-]", "_");
        if (visible.isEmpty()) visible = "game";
        if (visible.length() > 48) visible = visible.substring(0, 48);
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        String time = new SimpleDateFormat("HHmmss-SSS", Locale.ROOT).format(new Date());
        return new File(new File(new File(getCacheDir(), "tyranor-diagnostics/sessions"), visible),
            date + "/" + time + "-" + Long.toHexString(System.nanoTime()));
    }

    private void fail(int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show();
        File session = TynSession.current();
        if (session != null) {
            TynRuntimeDiagnostics.append(session, "Launch failed: " + getString(messageRes));
        }
        finish();
    }
}
