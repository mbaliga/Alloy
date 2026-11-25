package com.mbaliga.alloy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the bounded Bambu project recipe embedded in a local 3MF package. */
public final class BambuProjectSettingsExtractor {
    private static final String ENTRY = "Metadata/project_settings.config";
    private static final int MAX_BYTES = 256 * 1024;

    private BambuProjectSettingsExtractor() { }

    /**
     * Return the UTF-8 JSON document from a Bambu 3MF, or {@code null} when
     * the package is a plain model without project settings. Only the exact
     * standard metadata path is accepted; no package entry becomes a path on
     * the filesystem.
     */
    public static byte[] extract(File packageFile) throws IOException {
        if (packageFile == null || PrinterTransport.isSymbolicLink(packageFile)
                || !packageFile.isFile() || !packageFile.canRead())
            throw new IOException("3MF project package is not readable");
        ZipEntry found = null;
        try (ZipFile zip = new ZipFile(packageFile)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!ENTRY.equals(entry.getName())) continue;
                if (found != null) throw new IOException("3MF contains duplicate project settings");
                found = entry;
            }
            if (found == null) return null;
            long declared = found.getSize();
            if (declared > MAX_BYTES) throw new IOException("Bambu project settings exceed the 256 KB limit");
            try (InputStream input = zip.getInputStream(found)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        declared > 0L ? (int) declared : Math.min(MAX_BYTES, 8192));
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    if (output.size() > MAX_BYTES - count)
                        throw new IOException("Bambu project settings exceed the 256 KB limit");
                    output.write(buffer, 0, count);
                }
                if (output.size() == 0) throw new IOException("Bambu project settings are empty");
                return output.toByteArray();
            }
        }
    }
}
