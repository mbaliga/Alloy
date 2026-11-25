package com.mbaliga.alloy;

import android.graphics.BitmapFactory;

import java.io.IOException;

/** Provider boundary for private on-device or user-owned visualization models. */
public interface VisualizationProvider {
    /** A bounded request carries a rendered reference, never an unrestricted model file. */
    final class Request {
        public final byte[] referencePng;
        public final String prompt;

        public Request(byte[] referencePng, String prompt) {
            if (referencePng == null || referencePng.length == 0) throw new IllegalArgumentException("reference image is required");
            if (referencePng.length > 2 * 1024 * 1024) throw new IllegalArgumentException("reference image is too large");
            if (!isPng(referencePng)) throw new IllegalArgumentException("reference image must be PNG");
            if (prompt == null || prompt.trim().length() == 0 || prompt.length() > 2_000)
                throw new IllegalArgumentException("visualization prompt is invalid");
            this.referencePng = referencePng.clone();
            this.prompt = prompt.trim();
        }
    }

    final class Result {
        public final byte[] imagePng;
        public final String providerLabel;

        public Result(byte[] imagePng, String providerLabel) throws IOException {
            if (imagePng == null || imagePng.length == 0 || imagePng.length > 8 * 1024 * 1024)
                throw new IOException("visualization result is empty or too large");
            if (!isBoundedPng(imagePng)) throw new IOException("visualization result is not a bounded PNG");
            this.imagePng = imagePng.clone();
            this.providerLabel = providerLabel == null ? "Visualization" : providerLabel;
        }
    }

    /** Keep the provider boundary restricted to the rendered PNG contract. */
    static boolean isPng(byte[] value) {
        return value != null && value.length >= 8
                && (value[0] & 0xff) == 0x89 && value[1] == 0x50
                && value[2] == 0x4e && value[3] == 0x47
                && value[4] == 0x0d && value[5] == 0x0a
                && value[6] == 0x1a && value[7] == 0x0a;
    }

    /** Probe dimensions without allocating a bitmap, matching the renderer's limits. */
    static boolean isBoundedPng(byte[] value) {
        if (!isPng(value)) return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(value, 0, value.length, options);
        return options.outWidth > 0 && options.outHeight > 0
                && options.outWidth <= 2_048 && options.outHeight <= 2_048
                && (long) options.outWidth * options.outHeight <= 4_000_000L;
    }

    Result generate(Request request) throws IOException;
}
