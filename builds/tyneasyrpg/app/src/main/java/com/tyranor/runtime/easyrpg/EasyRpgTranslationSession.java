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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Consumes the TYN translation map URI (mtool-json: a flat UTF-8 JSON object
 * of original -> translated strings) and converts it to an in-memory gettext
 * catalog that is pushed straight into the engine (JNI). Nothing is written
 * to the game folder or to disk: the catalog lives only for the process
 * lifetime and is dropped when the game closes.
 *
 * The engine applies the catalog on the game thread (re-parses, reloads the
 * database and redraws), so updating the map at runtime is a hot reload.
 */
public final class EasyRpgTranslationSession {
    private static final String TAG = "TYN-EasyRPG[Translation]";
    private static final String REVISION_COLUMN = "revision";
    private static final long REVISION_HEARTBEAT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long NOTIFICATION_COALESCE_MS = 150;
    private static final long RETRY_MIN_MS = 1_000;
    private static final long RETRY_MAX_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long NATIVE_RETRY_MS = 500;
    private static final long NATIVE_RETRY_MAX_MS = 30_000;
    private static final int MAX_TRANSLATION_BYTES = 64 * 1024 * 1024;

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
    private ScheduledFuture<?> nativeRetryFuture;
    private volatile boolean closed;
    private volatile long lastRevision;
    private volatile boolean hasRevision;
    private volatile long nextRetryMs = RETRY_MIN_MS;
    private volatile int lastRecordCount;

    /** Latest built catalog; replaced atomically, referenced only by this class. */
    private volatile byte[] pendingCatalog;

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

    public static synchronized EasyRpgTranslationSession get(Context context) {
        if (instance == null) {
            instance = new EasyRpgTranslationSession(context);
        }
        return instance;
    }

    /**
     * Starts the session: builds the catalog in memory (background) and
     * watches the URI for updates. Never blocks the caller and never fails
     * the launch; without a usable map the engine runs the original text.
     */
    public synchronized void beginSession(@Nullable ContentResolver resolver, @Nullable Uri uri) {
        this.resolver = resolver;
        this.uri = uri;
        if (resolver != null && uri != null) {
            worker.execute(() -> {
                try {
                    buildAndPush("cold load");
                } catch (Exception error) {
                    Log.w(TAG, "Cold translation load failed; running with original text", error);
                    appendDiagnostic("Cold translation load failed: " + error);
                    scheduleRetry();
                }
            });
        } else {
            appendDiagnostic("No translation URI; running with original text");
        }
        registerObserver();
        startHeartbeat();
    }

    /** Called by the engine activity once the native libraries are loaded. */
    public void onEngineReady() {
        appendDiagnostic("Engine native libraries ready");
        pushPending();
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
        if (nativeRetryFuture != null) nativeRetryFuture.cancel(false);
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
                    buildAndPush("URI notification");
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
                    buildAndPush("retry");
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

    /** Reads the full map, converts it to an in-memory catalog and pushes it. */
    private synchronized void buildAndPush(String reason) throws IOException {
        if (resolver == null || uri == null) return;
        ByteArrayOutputStream po = readMapAsCatalog();
        pendingCatalog = po.toByteArray();
        appendDiagnostic("Converted translation map (" + reason + "): "
            + lastRecordCount + " record(s), catalog " + pendingCatalog.length + " bytes");
        pushPending();
    }

    private void pushPending() {
        byte[] catalog = pendingCatalog;
        if (catalog == null || closed) return;
        try {
            EasyRpgPlayerActivity.setTranslationCatalog(catalog);
            appendDiagnostic("Translation catalog pushed to the engine");
        } catch (UnsatisfiedLinkError linkError) {
            // Native libraries not loaded yet (cold load raced the engine
            // start): retry until they are.
            scheduleNativeRetry();
        }
    }

    private void scheduleNativeRetry() {
        if (closed) return;
        try {
            if (nativeRetryFuture != null) nativeRetryFuture.cancel(false);
            nativeRetryFuture = worker.schedule(this::pushPending,
                NATIVE_RETRY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Session closed.
        }
    }

    /** Streams the JSON map into PO text; the Map is never materialized. */
    private ByteArrayOutputStream readMapAsCatalog() throws IOException {
        ByteArrayOutputStream po = new ByteArrayOutputStream(64 * 1024);
        OutputStreamWriter writer = new OutputStreamWriter(po, StandardCharsets.UTF_8);
        int records = 0;
        try (InputStream raw = resolver.openInputStream(uri);
             CountingInputStream input = new CountingInputStream(raw, MAX_TRANSLATION_BYTES);
             JsonReader reader = new JsonReader(new BufferedReader(
                 new InputStreamReader(input, StandardCharsets.UTF_8)))) {
            reader.setLenient(true);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new IOException("Translation map root is not a JSON object");
            }
            // Dictionary::FromPo discards the first msgstr line as the header.
            writer.write("msgstr \"\"\n\n");
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                JsonToken token = reader.peek();
                if (token == JsonToken.STRING) {
                    String value = reader.nextString();
                    // Empty originals and empty translations are treated as
                    // untranslated so the engine keeps the original text.
                    if (!key.isEmpty() && !value.isEmpty()) {
                        writer.write("msgid \"");
                        writePoString(writer, key);
                        writer.write("\"\nmsgstr \"");
                        writePoString(writer, value);
                        writer.write("\"\n\n");
                        ++records;
                    }
                } else {
                    // Nested objects/arrays are not valid mtool-json entries.
                    reader.skipValue();
                }
            }
            reader.endObject();
            writer.flush();
        } catch (IOException error) {
            throw error;
        }
        lastRecordCount = records;
        return po;
    }

    /** Escapes per Dictionary::FromPo: only \\, \\n and \\\" are recognized. */
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
