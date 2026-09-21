package com.mbaliga.alloy;

import android.content.Intent;
import android.net.Uri;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Curated links to community model pages. The files stay on their original
 * host and enter Alloy only through the bounded, content-detecting importer.
 */
public final class CommunityModelCatalog {
    private CommunityModelCatalog() { }

    public static final class Entry {
        public final String name;
        public final String kind;
        public final String source;
        public final String url;
        public final String licenseNote;
        public final String safetyNote;

        private Entry(String name, String kind, String source, String url,
                      String licenseNote, String safetyNote) {
            this.name = name;
            this.kind = kind;
            this.source = source;
            this.url = url;
            this.licenseNote = licenseNote;
            this.safetyNote = safetyNote;
        }

        public String summary() {
            return name + "\n" + source + "  ·  " + kind;
        }

        public String details() {
            return source + "\n\nLicense status: " + licenseNote + "\n\n" + safetyNote
                    + "\n\nAfter downloading, use Android's Share/Open with → Alloy. Alloy will verify the model format before it enters the workspace.";
        }

        public Intent browserIntent() {
            return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        }
    }

    private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
            new Entry(
                    "Recurve bow · small printable model",
                    "Recurve",
                    "Pinshape · chemteacher628",
                    "https://pinshape.com/items/33841-3d-printed-recurve-bow",
                    "CC BY-ND is listed on the source page. Attribution is required; derivatives/remixes are not permitted under that license.",
                    "This is a small model, not a strength-rated archery component. Do not use printed parts as a weapon or for stored-energy loads."),
            new Entry(
                    "100% 3D printed compound bow",
                    "Compound",
                    "Cults3D · source page",
                    "https://cults3d.com/en/3d-model/game/100-3d-printed-compound-bow",
                    "The source page says personal-use only and prohibits redistribution. It is not bundled and should not be mirrored by Alloy.",
                    "Treat this as a model/experimental project only. Printed parts must not be assumed safe for drawing, firing, or other stored-energy use."),
            new Entry(
                    "Compound bow · MakerWorld listing",
                    "Compound",
                    "MakerWorld · source page",
                    "https://makerworld.com/es/models/1140708-compound-bow",
                    "The listing shows a Standard Digital File License. Read the current terms on the source page before downloading, modifying, or sharing.",
                    "Alloy does not endorse the design or its mechanical safety. Use only for visual inspection or a controlled, non-functional prototype unless independently engineered and tested.")
    ));

    public static List<Entry> entries() {
        return ENTRIES;
    }
}
