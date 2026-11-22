package com.tyranor.runtime.easyrpg;

import java.io.File;

/** Process-wide holder for the current diagnostics session directory. */
final class TynSession {
    private static volatile File currentSessionDir;

    static void setCurrent(File dir) {
        currentSessionDir = dir;
    }

    static File current() {
        return currentSessionDir;
    }

    private TynSession() {}
}
