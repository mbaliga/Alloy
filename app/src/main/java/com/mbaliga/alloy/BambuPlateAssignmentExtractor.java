package com.mbaliga.alloy;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Detects Bambu 3MF projects whose per-plate object assignment Alloy cannot yet preserve. */
public final class BambuPlateAssignmentExtractor {
    private static final String ENTRY = "Metadata/model_settings.config";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private BambuPlateAssignmentExtractor() { }

    /**
     * Return true when Bambu metadata declares more than one plate. Alloy currently
     * imports geometry as a single plate, so callers must reject these projects
     * instead of silently merging their plate contents.
     */
    public static boolean hasMultiplePlates(File packageFile) throws IOException {
        if (packageFile == null || PrinterTransport.isSymbolicLink(packageFile)
                || !packageFile.isFile() || !packageFile.canRead())
            throw new IOException("3MF project package is not readable");
        ZipEntry found = null;
        try (ZipFile zip = new ZipFile(packageFile)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!ENTRY.equals(entry.getName())) continue;
                if (found != null) throw new IOException("3MF contains duplicate Bambu model settings");
                found = entry;
            }
            if (found == null) return false;
            if (found.getSize() > MAX_BYTES)
                throw new IOException("Bambu model settings exceed the 16 MB safety limit");
            byte[] xml;
            try (InputStream input = zip.getInputStream(found)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        found.getSize() > 0 ? (int) found.getSize() : 8192);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    if (output.size() > MAX_BYTES - count)
                        throw new IOException("Bambu model settings exceed the 16 MB safety limit");
                    output.write(buffer, 0, count);
                }
                xml = output.toByteArray();
            }
            if (xml.length == 0) throw new IOException("Bambu model settings are empty");
            return countPlateElements(xml) > 1;
        }
    }

    private static int countPlateElements(byte[] xml) throws IOException {
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new java.io.ByteArrayInputStream(xml), "UTF-8");
            int count = 0;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "plate".equals(parser.getName())) {
                    if (++count > 1) return count;
                }
            }
            return count;
        } catch (Exception error) {
            throw new IOException("Could not read Bambu plate assignments from 3MF", error);
        }
    }
}
