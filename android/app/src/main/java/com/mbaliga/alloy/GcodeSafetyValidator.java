package com.mbaliga.alloy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, comment-aware checks for the small set of G-code invariants Alloy
 * needs before an artifact can leave the phone. This is deliberately not a
 * printer simulator: native/profile/hardware acceptance remains a separate
 * gate. It does ensure that required commands are real commands rather than
 * words hidden in comments or arbitrary metadata.
 */
public final class GcodeSafetyValidator {
    private static final int MAX_GCODE_CHARS = 128 * 1024 * 1024;
    private static final int MAX_LINE_CHARS = 1 * 1024 * 1024;
    private static final Pattern COMMAND = Pattern.compile(
            "^\\s*(?:N\\s*\\d+\\s+)?([GMT]\\s*\\d+)(?=\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern S_PARAMETER = Pattern.compile(
            "(?:^|\\s)S\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern E_PARAMETER = Pattern.compile(
            "(?:^|\\s)E\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern XY_PARAMETER = Pattern.compile(
            "(?:^|\\s)[XY]\\s*[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AXIS_PARAMETER = Pattern.compile(
            "([XYZEF])\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ARC_OFFSET_PARAMETER = Pattern.compile(
            "([IJR])\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)\\b",
            Pattern.CASE_INSENSITIVE);

    private GcodeSafetyValidator() { }

    /** Inspect an in-memory G-code string without copying it into line arrays. */
    public static Report inspect(String gcode) {
        if (gcode == null || gcode.trim().length() == 0) return Report.error("G-code output is empty");
        if (gcode.length() > MAX_GCODE_CHARS)
            return Report.error("G-code exceeds the 128 MB safety limit");
        Scanner scanner = new Scanner();
        scanner.accept(gcode);
        return scanner.finish();
    }

    /**
     * Strict artifact preflight. In addition to the format-level checks, a
     * printable result must home the machine, turn both heaters off, and keep
     * every explicit raw motion coordinate inside the selected build volume.
     * The preview model is not enough evidence: travel moves and end-gcode are
     * checked here before an artifact can be staged or sent to a printer.
     */
    public static Report inspect(String gcode, Slicer.Config config) {
        if (config == null) return inspect(gcode);
        if (!finite(config.bedX) || !finite(config.bedY) || !finite(config.bedZ)
                || config.bedX <= 0f || config.bedY <= 0f || config.bedZ <= 0f)
            return Report.error("G-code safety build volume is invalid");
        if (gcode == null || gcode.trim().length() == 0) return Report.error("G-code output is empty");
        if (gcode.length() > MAX_GCODE_CHARS)
            return Report.error("G-code exceeds the 128 MB safety limit");
        Scanner scanner = new Scanner(config);
        scanner.accept(gcode);
        return scanner.finish();
    }

    /** Fail closed for native output before it is wrapped as a printable artifact. */
    public static void requireSafe(String gcode) throws IOException {
        Report report = inspect(gcode);
        if (!report.isValid()) throw new IOException("G-code safety preflight failed: " + report.summary());
    }

    /** Fail closed against the configured build volume before artifact staging. */
    public static void requireSafe(String gcode, Slicer.Config config) throws IOException {
        Report report = inspect(gcode, config);
        if (!report.isValid()) throw new IOException("G-code safety preflight failed: " + report.summary());
    }

    /** Incremental scanner used by ZIP validation so large G-code is not reassembled. */
    public static final class Stream {
        private final Scanner scanner;
        private long totalBytes;

        public Stream() { this(null); }

        /**
         * Use the strict scanner for a package that is about to be recovered
         * or sent. The scanner keeps the build-volume and shutdown checks
         * incremental, so a large G-code entry is never copied in full.
         */
        public Stream(Slicer.Config config) { scanner = new Scanner(config); }

        public void accept(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length)
                throw new IOException("Invalid G-code chunk");
            if (length > MAX_GCODE_CHARS - totalBytes)
                throw new IOException("G-code exceeds the 128 MB safety limit");
            totalBytes += length;
            scanner.accept(new String(bytes, offset, length, StandardCharsets.UTF_8));
        }

        public Report finish() { return scanner.finish(); }
    }

    public static final class Report {
        public final ArrayList<String> errors;
        public final boolean hasAbsolutePositioning;
        public final boolean hasExtrusionMode;
        public final boolean hasStopTemperature;
        public final boolean hasStopBed;
        public final boolean hasHoming;
        public final boolean hasMotion;
        public final boolean hasPrintableMotion;

        private Report(ArrayList<String> errors, boolean hasAbsolutePositioning,
                       boolean hasExtrusionMode, boolean hasStopTemperature, boolean hasStopBed,
                       boolean hasHoming,
                       boolean hasMotion, boolean hasPrintableMotion) {
            this.errors = errors;
            this.hasAbsolutePositioning = hasAbsolutePositioning;
            this.hasExtrusionMode = hasExtrusionMode;
            this.hasStopTemperature = hasStopTemperature;
            this.hasStopBed = hasStopBed;
            this.hasHoming = hasHoming;
            this.hasMotion = hasMotion;
            this.hasPrintableMotion = hasPrintableMotion;
        }

        private static Report error(String error) {
            ArrayList<String> errors = new ArrayList<>();
            errors.add(error);
            return new Report(errors, false, false, false, false, false, false, false);
        }

        public boolean isValid() { return errors.isEmpty(); }

        public String summary() {
            return errors.isEmpty() ? "G-code passed safety preflight" : errors.get(0);
        }
    }

    private static final class Scanner {
        private final ArrayList<String> errors = new ArrayList<>();
        private final StringBuilder pending = new StringBuilder();
        private final Slicer.Config strictConfig;
        private boolean hasAbsolutePositioning;
        private boolean hasExtrusionMode;
        private boolean hasStopTemperature;
        private boolean hasStopBed;
        private boolean hasHoming;
        private boolean hasMotion;
        private boolean hasPrintableMotion;
        private boolean absoluteXYZ = true;
        private boolean absoluteE = true;
        private double x;
        private double y;
        private double z;
        private double e;
        private int lineNumber;
        private boolean finished;

        Scanner() { this(null); }

        Scanner(Slicer.Config strictConfig) {
            this.strictConfig = strictConfig;
        }

        void accept(CharSequence text) {
            if (finished || text == null) return;
            for (int i = 0; i < text.length(); i++) {
                char value = text.charAt(i);
                if (value == '\n') {
                    scanLine(pending.toString());
                    pending.setLength(0);
                } else {
                    if (pending.length() < MAX_LINE_CHARS) pending.append(value);
                    if (pending.length() >= MAX_LINE_CHARS && errors.isEmpty())
                        errors.add("G-code line " + (lineNumber + 1) + " exceeds the 1 MB limit");
                }
            }
        }

        Report finish() {
            if (!finished) {
                if (pending.length() > 0) scanLine(pending.toString());
                finished = true;
                if (!hasAbsolutePositioning) errors.add("G-code is missing a G90 command");
                if (!hasExtrusionMode) errors.add("G-code is missing an M82 or M83 command");
                if (!hasStopTemperature) errors.add("G-code is missing an M104 S0 command");
                if (!hasMotion) errors.add("G-code contains no movement command");
                if (!hasPrintableMotion) errors.add("G-code contains no printable extrusion move");
                if (strictConfig != null) {
                    if (!hasHoming) errors.add("G-code is missing a G28 homing command");
                    if (!hasStopBed) errors.add("G-code is missing an M140 S0 bed shutdown command");
                }
            }
            return new Report(new ArrayList<>(errors), hasAbsolutePositioning, hasExtrusionMode,
                    hasStopTemperature, hasStopBed, hasHoming, hasMotion, hasPrintableMotion);
        }

        private void scanLine(String raw) {
            lineNumber++;
            String commandLine = removeCommentsAndChecksum(raw);
            Matcher commandMatcher = COMMAND.matcher(commandLine);
            if (!commandMatcher.find()) return;
            String code = commandMatcher.group(1).replace(" ", "").toUpperCase(Locale.US);
            if ("G90".equals(code)) {
                hasAbsolutePositioning = true;
                absoluteXYZ = true;
            } else if ("G91".equals(code)) {
                absoluteXYZ = false;
            } else if ("M82".equals(code) || "M83".equals(code)) {
                hasExtrusionMode = true;
                absoluteE = "M82".equals(code);
            } else if ("M104".equals(code)) {
                Matcher temperature = S_PARAMETER.matcher(commandLine);
                if (temperature.find()) {
                    try {
                        double value = Double.parseDouble(temperature.group(1));
                        if (Double.isFinite(value) && Math.abs(value) <= 0.0001d) hasStopTemperature = true;
                    } catch (NumberFormatException ignored) { }
                }
            } else if ("M140".equals(code)) {
                Matcher temperature = S_PARAMETER.matcher(commandLine);
                if (temperature.find()) {
                    try {
                        double value = Double.parseDouble(temperature.group(1));
                        if (Double.isFinite(value) && Math.abs(value) <= 0.0001d) hasStopBed = true;
                    } catch (NumberFormatException ignored) { }
                }
            } else if ("G28".equals(code)) {
                hasHoming = true;
                if (strictConfig != null) {
                    boolean xAxis = hasAxis(commandLine, 'X');
                    boolean yAxis = hasAxis(commandLine, 'Y');
                    boolean zAxis = hasAxis(commandLine, 'Z');
                    if (!xAxis && !yAxis && !zAxis) x = y = z = 0d;
                    else {
                        if (xAxis) x = 0d;
                        if (yAxis) y = 0d;
                        if (zAxis) z = 0d;
                    }
                }
            } else if ("G92".equals(code)) {
                if (strictConfig != null) updatePosition(commandLine, true);
            } else if ("G0".equals(code) || "G1".equals(code) || "G2".equals(code) || "G3".equals(code)) {
                hasMotion = true;
                Matcher xy = XY_PARAMETER.matcher(commandLine);
                Matcher extrusion = E_PARAMETER.matcher(commandLine);
                if (xy.find() && extrusion.find()) {
                    try {
                        if (Double.parseDouble(extrusion.group(1)) > 0d) hasPrintableMotion = true;
                    } catch (NumberFormatException ignored) { }
                }
                if (strictConfig != null) updatePosition(commandLine, false,
                        "G2".equals(code) || "G3".equals(code), "G3".equals(code));
            }
        }

        private void updatePosition(String commandLine, boolean reset) {
            updatePosition(commandLine, reset, false, false);
        }

        private void updatePosition(String commandLine, boolean reset, boolean arc, boolean counterClockwise) {
            double startX = x;
            double startY = y;
            double i = Double.NaN;
            double j = Double.NaN;
            boolean radiusArc = false;
            if (arc) {
                Matcher offsets = ARC_OFFSET_PARAMETER.matcher(commandLine);
                while (offsets.find()) {
                    char axis = Character.toUpperCase(offsets.group(1).charAt(0));
                    try {
                        double value = Double.parseDouble(offsets.group(2));
                        if (!Double.isFinite(value) || Math.abs(value) > 1_000_000d)
                            addError("G-code contains an unsafe arc parameter at line " + lineNumber);
                        else if (axis == 'I') i = value;
                        else if (axis == 'J') j = value;
                        else if (axis == 'R') radiusArc = true;
                    } catch (NumberFormatException error) {
                        addError("G-code contains an invalid arc parameter at line " + lineNumber);
                    }
                }
                if (radiusArc && (Double.isNaN(i) || Double.isNaN(j)))
                    addError("G-code radius arcs cannot be bounded safely at line " + lineNumber);
            }
            Matcher matcher = AXIS_PARAMETER.matcher(commandLine);
            while (matcher.find()) {
                char axis = Character.toUpperCase(matcher.group(1).charAt(0));
                double value;
                try {
                    value = Double.parseDouble(matcher.group(2));
                } catch (NumberFormatException error) {
                    addError("G-code contains an invalid " + axis + " coordinate at line " + lineNumber);
                    continue;
                }
                if (!Double.isFinite(value) || Math.abs(value) > 1_000_000d) {
                    addError("G-code contains an unsafe " + axis + " coordinate at line " + lineNumber);
                    continue;
                }
                if (axis == 'X') x = reset || absoluteXYZ ? value : x + value;
                else if (axis == 'Y') y = reset || absoluteXYZ ? value : y + value;
                else if (axis == 'Z') z = reset || absoluteXYZ ? value : z + value;
                else if (axis == 'E') e = reset || absoluteE ? value : e + value;
            }
            if (reset) return;
            double tolerance = 0.5d;
            if (outsideBed(x, y, tolerance))
                addError("G-code moves outside the " + format(strictConfig.bedX) + " × "
                        + format(strictConfig.bedY) + " mm build plate at line " + lineNumber);
            if (arc && !Double.isNaN(i) && !Double.isNaN(j) && !radiusArc)
                checkArcEnvelope(startX, startY, x, y, i, j, counterClockwise, tolerance);
            // A small positive Z park after the final layer is normal; permit
            // only that bounded clearance and reject genuine over-travel.
            if (z < -tolerance || z > strictConfig.bedZ + 5d)
                addError("G-code moves outside the configured Z range at line " + lineNumber);
        }

        private boolean outsideBed(double pointX, double pointY, double tolerance) {
            return pointX < -tolerance || pointX > strictConfig.bedX + tolerance
                    || pointY < -tolerance || pointY > strictConfig.bedY + tolerance;
        }

        /** Check arc extrema, not only the endpoint, against the build plate. */
        private void checkArcEnvelope(double startX, double startY, double endX, double endY,
                                      double i, double j, boolean counterClockwise, double tolerance) {
            double centerX = startX + i;
            double centerY = startY + j;
            double radius = Math.hypot(i, j);
            if (!Double.isFinite(radius) || radius <= 1e-9) {
                addError("G-code contains an invalid arc radius at line " + lineNumber);
                return;
            }
            if (outsideBed(startX, startY, tolerance)) return;
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                double angle = quadrant * Math.PI / 2d;
                double pointX = centerX + radius * Math.cos(angle);
                double pointY = centerY + radius * Math.sin(angle);
                if (onArcSweep(startX, startY, endX, endY, centerX, centerY,
                        angle, counterClockwise)
                        && outsideBed(pointX, pointY, tolerance)) {
                    addError("G-code arc leaves the " + format(strictConfig.bedX) + " × "
                            + format(strictConfig.bedY) + " mm build plate at line " + lineNumber);
                    return;
                }
            }
        }

        private static boolean onArcSweep(double startX, double startY, double endX, double endY,
                                          double centerX, double centerY, double candidateAngle,
                                          boolean counterClockwise) {
            double start = Math.atan2(startY - centerY, startX - centerX);
            double end = Math.atan2(endY - centerY, endX - centerX);
            double candidate = normalizeAngle(candidateAngle);
            start = normalizeAngle(start);
            end = normalizeAngle(end);
            if (counterClockwise) {
                double sweep = normalizeAngle(end - start);
                double distance = normalizeAngle(candidate - start);
                return distance <= sweep + 1e-9;
            }
            double sweep = normalizeAngle(start - end);
            double distance = normalizeAngle(start - candidate);
            return distance <= sweep + 1e-9;
        }

        private static double normalizeAngle(double angle) {
            double normalized = angle % (Math.PI * 2d);
            return normalized < 0d ? normalized + Math.PI * 2d : normalized;
        }

        private static boolean hasAxis(String commandLine, char wanted) {
            Matcher matcher = AXIS_PARAMETER.matcher(commandLine);
            while (matcher.find()) if (Character.toUpperCase(matcher.group(1).charAt(0)) == wanted) return true;
            return false;
        }

        private void addError(String error) {
            if (errors.size() < 64) errors.add(error);
        }

        private static String format(float value) {
            return String.format(Locale.US, "%.0f", value);
        }

        private static String removeCommentsAndChecksum(String raw) {
            StringBuilder result = new StringBuilder(raw.length());
            boolean parentheticalComment = false;
            for (int i = 0; i < raw.length(); i++) {
                char value = raw.charAt(i);
                if (value == ';') break;
                if (value == '*') break;
                if (value == '(') { parentheticalComment = true; continue; }
                if (value == ')') { parentheticalComment = false; continue; }
                if (!parentheticalComment) result.append(value);
            }
            return result.toString();
        }
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
