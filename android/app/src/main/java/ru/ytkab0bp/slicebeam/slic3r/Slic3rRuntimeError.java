package ru.ytkab0bp.slicebeam.slic3r;

/** Temporary exception type required by the pinned upstream JNI exports. */
public class Slic3rRuntimeError extends Exception {
    public Slic3rRuntimeError() { }
    public Slic3rRuntimeError(String message) { super(message); }
}
