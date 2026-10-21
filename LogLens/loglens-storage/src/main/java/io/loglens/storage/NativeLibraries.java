package io.loglens.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Windows holds loaded DLLs open until JVM exit; do not extract into per-session/scratch data. */
final class NativeLibraries {
    private NativeLibraries() {}
    static synchronized void configure(Path fallback) throws IOException {
        String configured = System.getProperty("org.sqlite.tmpdir");
        Path nativeDirectory = configured == null ? fallback.toAbsolutePath().normalize() : Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(nativeDirectory);
        System.setProperty("org.sqlite.tmpdir", nativeDirectory.toString());
    }
}
