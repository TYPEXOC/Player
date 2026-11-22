package com.tyranor.runtime.easyrpg;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import com.tyranor.runtime.diagnostics.TynRuntimeDiagnostics;
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
 * translation catalog and starts the EasyRPG engine activity. The module
 * itself never launches games on its own; the launcher icon opens the
 * module info page instead.
 */
public class MainActivity extends Activity {
    private static final String TAG = "TYN-EasyRPG";

    private static final String RUNTIME_MODULE_ACTION_LAUNCH = "com.tyranor.runtime.action.LAUNCH";
    private static final String EXTRA_FOLDER_PATH = "com.tyranor.runtime.extra.FOLDER_PATH";
    private static final String EXTRA_GAME_TITLE = "com.tyranor.runtime.extra.GAME_TITLE";
    private static final String EXTRA_PROTOCOL_VERSION = "com.tyranor.runtime.extra.PROTOCOL_VERSION";
    private static final String EXTRA_SETTINGS = "com.tyranor.runtime.extra.SETTINGS";
    private static final String EXTRA_TRANSLATION_MAP_URI = "com.tyranor.runtime.extra.TRANSLATION_MAP_URI";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (!RUNTIME_MODULE_ACTION_LAUNCH.equals(intent.getAction())) {
            // Opened directly (launcher tap): show the module info page.
            startActivity(new Intent(this, ModuleInfoActivity.class));
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
            Toast.makeText(this, R.string.tyn_launch_no_storage, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, ModuleInfoActivity.class));
            finish();
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

        // Cold load the translation catalog before the native game thread starts.
        EasyRpgTranslationSession session = EasyRpgTranslationSession.get(this);
        int translationCount = session.startForLaunch(getContentResolver(), mapUri);

        EasyRpgPlayerActivity.tynHidePad = !ModulePrefs.isPadEnabled(this);
        session.markEngineStarted();

        List<String> args = new ArrayList<>();
        args.add(gameDir.getAbsolutePath());
        if (ModulePrefs.isCheatsEnabled(this)) {
            // Same directory the shared cheat controller (Kotlin side) uses.
            args.add("--tyneasyrpg-cheat-dir");
            args.add(getCacheDir().getAbsolutePath());
        }
        if (translationCount > 0) {
            args.add("--language-path");
            args.add(session.getLanguageRootPath());
            args.add("--language");
            args.add(session.getLanguageId());
        }
        String encoding = ModulePrefs.getEncoding(this);
        if (!encoding.trim().isEmpty()) {
            args.add("--encoding");
            args.add(encoding.trim());
        }

        Intent engine = new Intent(this, EasyRpgPlayerActivity.class);
        engine.putExtra(EasyRpgPlayerActivity.TAG_COMMAND_LINE, args.toArray(new String[0]));
        engine.putExtra(EasyRpgPlayerActivity.TAG_SAVE_PATH, gameDir.getAbsolutePath());
        startActivity(engine);
        TynRuntimeDiagnostics.append(TynSession.current(),
            "Engine activity started with " + args.size() + " argument(s), translation records=" + translationCount);
        finish();
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
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
