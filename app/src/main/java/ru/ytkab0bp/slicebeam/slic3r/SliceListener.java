package ru.ytkab0bp.slicebeam.slic3r;

/** Temporary JNI compatibility callback for the pinned SliceBeam native tree. */
public interface SliceListener {
    void onProgress(int progress, String text);
}
