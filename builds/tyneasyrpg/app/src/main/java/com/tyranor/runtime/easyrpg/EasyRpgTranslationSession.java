package com.tyranor.runtime.easyrpg;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import androidx.annotation.Nullable;

import org.easyrpg.player.player.EasyRpgPlayerActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Consumes the TYN translation map URI (mtool-json: a flat UTF-8 JSON object
 * of original -> translated strings) and materializes it as an EasyRPG
 * language catalog in module-private storage.
 *
 * The engine never reads the game directory for translations: MainActivity
 * passes --language-path/--language pointing at the private cache, so the
 * game folder stays untouched. URI updates are re-materialized and pushed to
 * the running engine through the requestTranslationReload JNI entry.
 */
final class EasyRpgTranslationSession {
    private static final String TAG = "TYN-EasyRPG[Translation]";
    private static final String REVISION_COLUMN = "revision";
    private static final long REVISION_HEARTBEAT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long NOTIFICATION_COALESCE_MS = 150;
    private static final long RETRY_MIN_MS = 1_000;
    private static final long RETRY_MAX_MS = TimeUnit.SECONDS.toMillis(10);
    private static final int MAX_TRANSLATION_BYTES = 64 * 1024 * 1024;

    /** Language id exposed through --language; one fixed catalog per session. */
    private static final String LANGUAGE_ID = "tyn";
    private static final String PO_FILE_NAME = "rpg_rt.ldb.po";
    private static final String PO_TMP_NAME = "rpg_rt.ldb.po.tmp";

    private static volatile EasyRpgTranslationSession instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TYN-EasyRPG-translation");
        thread.setDaemon(true);
        return thread;
    });

    private ContentResolver resolver;
    private Uri uri;
    private boolean observerRegistered;
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> retryFuture;
    private volatile boolean closed;
    private volatile boolean engineStarted;
    private volatile boolean hasSuccessfulLoad;
    private volatile long lastRevision;
    private volatile boolean hasRevision;
    private volatile long nextRetryMs = RETRY_MIN_MS;
    private volatile int lastRecordCount;

    private final ContentObserver observer = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            scheduleRefresh("URI notification");
        }

        @Override
        public void onChange(boolean selfChange, Uri changedUri) {
            scheduleRefresh("URI notification");
        }
    };

    private EasyRpgTranslationSession(Context context) {
        this.appContext = context.getApplicationContext();
    }

    static synchronized EasyRpgTranslationSession get(Context context) {
        if (instance == null) {
            instance = new EasyRpgTranslationSession(context);
        }
        return instance;
    }

    /** Absolute path handed to the engine as --language-path. */
    String getLanguageRootPath() {
        return languageRoot().getAbsolutePath();
    }

    String getLanguageId() {
        return LANGUAGE_ID;
    }

    /**
     * Blocking cold load before the native game thread starts. Returns the
     * number of records in the active map; 0 means "show original text".
     * Never throws for map-level failures: the game must start regardless.
     */
    synchronized int startForLaunch(@Nullable ContentResolver resolver, @Nullable Uri uri) {
        this.resolver = resolver;
        this.uri = uri;
        int count = 0;
        if (resolver != null && uri != null) {
            try {
                count = refreshMap("cold load");
            } catch (Exception error) {
                Log.w(TAG, "Cold translation load failed; launching with original text", error);
                appendDiagnostic("Cold translation load failed: " + error);
                scheduleRetry();
            }
        } else {
            appendDiagnostic("No translation URI; launching with original text");
        }
        registerObserver();
        startHeartbeat();
        return count;
    }

    /** Marks that the engine activity is running; later updates push reloads to native. */
    void markEngineStarted() {
        engineStarted = true;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        if (observerRegistered && resolver != null) {
            try {
                resolver.unregisterContentObserver(observer);
            } catch (Exception ignored) {
                // Observer may already be unregistered.
            }
            observerRegistered = false;
        }
        if (heartbeatFuture != null) heartbeatFuture.cancel(false);
        if (retryFuture != null) retryFuture.cancel(false);
        worker.shutdown();
    }

    private void registerObserver() {
        if (closed || observerRegistered || resolver == null || uri == null) return;
        try {
            resolver.registerContentObserver(uri, false, observer);
            observerRegistered = true;
            appendDiagnostic("Translation URI observer registered");
        } catch (Exception error) {
            Log.w(TAG, "Could not register translation URI observer", error);
            appendDiagnostic("Translation URI observer registration failed; revision heartbeat remains", error);
        }
    }

    private void startHeartbeat() {
        if (closed || resolver == null || uri == null) return;
        heartbeatFuture = worker.scheduleWithFixedDelay(this::pollRevision,
            REVISION_HEARTBEAT_MS, REVISION_HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    private void pollRevision() {
        if (closed || resolver == null || uri == null) return;
        try {
            long revision = lastRevision;
            boolean sawRevision = false;
            try (Cursor cursor = resolver.query(uri, new String[]{REVISION_COLUMN}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndexOrThrow(REVISION_COLUMN);
                    revision = cursor.getLong(column);
                    sawRevision = true;
                }
            }
            if (!sawRevision) return;
            boolean changed = !hasRevision || revision != lastRevision;
            lastRevision = revision;
            hasRevision = true;
            if (changed) {
                scheduleRefresh("revision " + revision);
            }
        } catch (Exception error) {
            Log.w(TAG, "Revision heartbeat failed", error);
        }
    }

    private final Runnable notificationRefresh = this::refreshFromNotification;

    private void scheduleRefresh(String reason) {
        if (closed) return;
        cancelRetry();
        mainHandler.removeCallbacks(notificationRefresh);
        mainHandler.postDelayed(notificationRefresh, NOTIFICATION_COALESCE_MS);
        Log.d(TAG, "Scheduled translation refresh (" + reason + ")");
    }

    private void refreshFromNotification() {
        if (closed) return;
        try {
            worker.execute(() -> {
                try {
                    refreshMap("URI notification");
                } catch (Exception error) {
                    Log.w(TAG, "Translation refresh failed", error);
                    appendDiagnostic("Translation refresh failed: " + error);
                    scheduleRetry();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Session closed while a callback was pending.
        }
    }

    private void scheduleRetry() {
        if (closed || resolver == null || uri == null) return;
        long delay = nextRetryMs;
        nextRetryMs = Math.min(nextRetryMs * 2, RETRY_MAX_MS);
        try {
            if (retryFuture != null) retryFuture.cancel(false);
            retryFuture = worker.schedule(() -> {
                try {
                    refreshMap("retry");
                } catch (Exception error) {
                    Log.w(TAG, "Translation retry failed", error);
                    scheduleRetry();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Session closed.
        }
    }

    private void cancelRetry() {
        nextRetryMs = RETRY_MIN_MS;
        if (retryFuture != null) {
            retryFuture.cancel(false);
            retryFuture = null;
        }
    }

    /** Reads the full map and rewrites the private catalog atomically. */
    private synchronized int refreshMap(String reason) throws IOException {
        if (resolver == null || uri == null) return 0;
        Map<String, String> map = readMap();
        writeCatalog(map);
        hasSuccessfulLoad = true;
        lastRecordCount = map.size();
        appendDiagnostic("Loaded translation map (" + reason + "): " + map.size() + " record(s)");
        if (engineStarted) {
            // The native game loop performs the actual reload.
            EasyRpgPlayerActivity.requestTranslationReload();
        }
        return lastRecordCount;
    }

    private Map<String, String> readMap() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        try (InputStream raw = resolver.openInputStream(uri);
             CountingInputStream input = new CountingInputStream(raw, MAX_TRANSLATION_BYTES);
             JsonReader reader = new JsonReader(new BufferedReader(
                 new InputStreamReader(input, StandardCharsets.UTF_8)))) {
            reader.setLenient(true);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new IOException("Translation map root is not a JSON object");
            }
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                JsonToken token = reader.peek();
                if (token == JsonToken.STRING) {
                    String value = reader.nextString();
                    // Empty originals and empty translations are treated as
                    // untranslated so the engine keeps the original text.
                    if (!key.isEmpty() && !value.isEmpty()) {
                        map.put(key, value);
                    }
                } else {
                    // Nested objects/arrays are not valid mtool-json entries.
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        return map;
    }

    /** Atomically rewrites rpg_rt.ldb.po; the engine always sees a whole file. */
    private void writeCatalog(Map<String, String> map) throws IOException {
        File root = new File(languageRoot(), LANGUAGE_ID);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Could not create translation catalog directory");
        }
        writeMetaIni(root);
        File target = new File(root, PO_FILE_NAME);
        File tmp = new File(root, PO_TMP_NAME);
        try (OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(tmp, false), StandardCharsets.UTF_8)) {
            // Dictionary::FromPo discards the first msgstr line as the header.
            writer.write("msgstr \"\"\n\n");
            for (Map.Entry<String, String> entry : map.entrySet()) {
                writer.write("msgid \"");
                writePoString(writer, entry.getKey());
                writer.write("\"\nmsgstr \"");
                writePoString(writer, entry.getValue());
                writer.write("\"\n\n");
            }
            writer.flush();
        }
        try {
            Files.move(tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeMetaIni(File root) throws IOException {
        File meta = new File(root, "meta.ini");
        if (meta.isFile()) return;
        try (OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(meta, false), StandardCharsets.UTF_8)) {
            writer.write("[Language]\n");
            writer.write("Name=TYN Translation\n");
        }
    }

    /** Escapes per Dictionary::FromPo: only \\, \n and \" are recognized. */
    private static void writePoString(OutputStreamWriter writer, String value) throws IOException {
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    writer.write("\\\\");
                    break;
                case '\n':
                    writer.write("\\n");
                    break;
                case '"':
                    writer.write("\\\"");
                    break;
                default:
                    writer.write(c);
            }
        }
    }

    private File languageRoot() {
        return new File(appContext.getCacheDir(), "tyn-translation");
    }

    private void appendDiagnostic(String message) {
        File sessionDir = TynSession.current();
        if (sessionDir != null) {
            com.tyranor.runtime.diagnostics.TynRuntimeDiagnostics.append(sessionDir, message);
        }
    }

    private void appendDiagnostic(String message, Throwable error) {
        File sessionDir = TynSession.current();
        if (sessionDir != null) {
            com.tyranor.runtime.diagnostics.TynRuntimeDiagnostics.append(sessionDir, message, error);
        }
    }

    /** Guard wrapper enforcing the translation payload size limit. */
    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        private long count;

        CountingInputStream(@Nullable InputStream delegate, long limit) throws IOException {
            if (delegate == null) throw new IOException("Could not open translation URI stream");
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0 && ++count > limit) throw new IOException("Translation map exceeds size limit");
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count += read;
                if (count > limit) throw new IOException("Translation map exceeds size limit");
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
