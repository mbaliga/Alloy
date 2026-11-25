package com.mbaliga.alloy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed identity and placeholder checks for Alloy's A1 Mini machine
 * template. This is intentionally narrower than a firmware validator: it
 * proves that the native artifact came from Alloy's reviewed safe baseline and
 * contains no unresolved Orca/Bambu template language before it is persisted
 * or packaged.
 */
public final class A1MiniTemplatePolicy {
    public static final String ID = "alloy-safe-a1-mini-v1";
    private static final String START_MARKER = "; Alloy native start · ";
    private static final String END_MARKER = "; Alloy native end";
    private static final String POLICY_MARKER = "; Alloy template policy: " + ID;
    private static final Pattern UNRESOLVED = Pattern.compile(
            "\\[[A-Za-z_][A-Za-z0-9_.]*(?:\\[[^\\]\\r\\n]+\\])?\\]|\\{[^}\\r\\n]+\\}");
    private static final Pattern COMMAND = Pattern.compile(
            "^\\s*(?:N\\s*\\d+\\s+)?([GMT]\\s*\\d+(?:\\.\\d+)?)(?=\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZERO_PARAMETER = Pattern.compile(
            "(?:^|\\s)S\\s*[-+]?0(?:\\.0*)?(?=\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern G92_E_ZERO = Pattern.compile(
            "^\\s*(?:N\\s*\\d+\\s+)?G\\s*92\\b.*(?:^|\\s)E\\s*[-+]?0(?:\\.0*)?(?=\\s|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_DEFAULT_ACCELERATION = Pattern.compile(
            "^M\\s*204\\s+S\\s*[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)$",
            Pattern.CASE_INSENSITIVE);

    private A1MiniTemplatePolicy() { }

    /** Inspect one fully rendered native A1 Mini artifact. */
    public static Report inspect(String gcode) {
        ArrayList<String> errors = new ArrayList<>();
        if (gcode == null || gcode.trim().length() == 0) {
            errors.add("G-code is empty");
            return new Report(errors, false, false, false);
        }
        if (gcode.indexOf(POLICY_MARKER) < 0) errors.add("native template policy identity is missing");

        boolean unresolved = false;
        int startLine = -1;
        int endLine = -1;
        int firstLayerLine = -1;
        int lineNumber = 0;
        String[] lines = gcode.split("\\r?\\n", -1);
        for (String raw : lines) {
            lineNumber++;
            String trimmedRaw = raw.trim();
            // The native writer also records the escaped setting in the
            // generated config header. Match only the actual standalone
            // marker line so that that diagnostic copy cannot create a fake
            // second shutdown section.
            if (trimmedRaw.startsWith(START_MARKER)) {
                if (startLine >= 0) errors.add("native start template identity appears more than once");
                else startLine = lineNumber;
            }
            if (trimmedRaw.equals(END_MARKER)) {
                if (endLine >= 0) errors.add("native end template identity appears more than once");
                else endLine = lineNumber;
            }
            String marker = trimmedRaw.toUpperCase(Locale.US);
            if (firstLayerLine < 0 && (marker.startsWith(";LAYER_CHANGE")
                    || marker.startsWith("; LAYER_CHANGE")
                    || marker.startsWith(";LAYER:")
                    || marker.startsWith("; LAYER:"))) firstLayerLine = lineNumber;
            String line = commandText(raw);
            if (line.length() == 0) continue;
            Matcher unresolvedMatcher = UNRESOLVED.matcher(line);
            if (unresolvedMatcher.find()) {
                unresolved = true;
                if (errors.size() < 24)
                    errors.add("unresolved template token: " + unresolvedMatcher.group());
            }
            Matcher commandMatcher = COMMAND.matcher(line);
            if (!commandMatcher.find()) continue;
            // Keep the command scan above intentionally small: the actual
            // acceptance check below is bounded to the marker-defined
            // startup/shutdown ranges. A command in the model body must not
            // satisfy a missing template command.
        }
        boolean hasStart = startLine >= 0;
        boolean hasEnd = endLine >= 0;
        if (!hasStart) errors.add("native start template identity is missing");
        if (!hasEnd) errors.add("native end template identity is missing");
        if (hasStart && hasEnd && endLine <= startLine)
            errors.add("native end template identity precedes the native start template");
        requireAllowedCommands(lines, startLine, firstLayerLine > 0 ? firstLayerLine : endLine,
                errors, "startup");
        requireAllowedCommands(lines, endLine, lines.length + 1, errors, "shutdown");
        // A command elsewhere in a long G-code file is not evidence that it
        // was emitted by the reviewed template. Bound startup at the first
        // layer marker and shutdown at its identity marker, then require the
        // reviewed command order in those exact ranges.
        String[] requiredStart = {"M140", "M104", "M190", "M109", "G28", "G90", "M83", "G92 E0", "M107"};
        requireOrderedCommands(lines, startLine, firstLayerLine > 0 ? firstLayerLine : endLine,
                requiredStart, errors, "native start command is missing or reordered: ");
        String[] requiredEnd = {"M104 S0", "M140 S0", "M107", "M84"};
        requireOrderedShutdown(lines, endLine, requiredEnd, errors);
        if (errors.size() > 24) errors.subList(24, errors.size()).clear();
        return new Report(errors, hasStart, hasEnd, unresolved);
    }

    /** Reject firmware-specific macros in the reviewed template sections. */
    private static void requireAllowedCommands(String[] lines, int fromLine, int toLine,
                                               ArrayList<String> errors, String section) {
        if (fromLine < 1 || toLine <= fromLine) return;
        for (int index = fromLine; index < toLine && index <= lines.length; index++) {
            String raw = lines[index - 1];
            String commandText = commandText(raw);
            if (commandText.length() == 0) continue;
            String command = normalizedCommand(raw);
            if (!isAllowedTemplateCommand(command)) {
                errors.add(section + " template command is outside Alloy allowlist: " + command);
            }
        }
    }

    private static boolean isAllowedTemplateCommand(String command) {
        return "M73".equals(command) || "M104".equals(command) || "M104 S0".equals(command)
                || "M106".equals(command) || "M107".equals(command) || "M140".equals(command)
                || "M140 S0".equals(command) || "M190".equals(command)
                || "M109".equals(command) || "G28".equals(command)
                || "G21".equals(command) || "G90".equals(command) || "G91".equals(command)
                || "M83".equals(command) || "G92 E0".equals(command)
                || "M204 S".equals(command)
                || "G1".equals(command) || "M84".equals(command);
    }

    private static void requireOrderedCommands(String[] lines, int fromLine, int toLine,
                                               String[] required, ArrayList<String> errors,
                                               String messagePrefix) {
        if (fromLine < 1 || toLine <= fromLine) {
            for (String command : required) errors.add(messagePrefix + command);
            return;
        }
        int next = 0;
        for (int index = fromLine; index < toLine && next < required.length; index++) {
            if (matchesRequiredCommand(normalizedCommand(lines[index - 1]), required[next])) next++;
        }
        while (next < required.length) errors.add(messagePrefix + required[next++]);
    }

    private static void requireOrderedShutdown(String[] lines, int markerLine, String[] required,
                                               ArrayList<String> errors) {
        if (markerLine < 1) {
            for (String command : required)
                errors.add("native shutdown is missing or reordered " + command);
            return;
        }
        int next = 0;
        for (int index = markerLine; index <= lines.length && next < required.length; index++) {
            if (matchesRequiredCommand(normalizedCommand(lines[index - 1]), required[next])) next++;
        }
        while (next < required.length)
            errors.add("native shutdown is missing or reordered " + required[next++]);
    }

    private static boolean matchesRequiredCommand(String command, String required) {
        if (command == null || command.length() == 0) return false;
        return command.equals(required) || (!required.contains(" ") && command.equals(required));
    }

    private static String normalizedCommand(String raw) {
        String line = commandText(raw);
        if (line.length() == 0) return "";
        Matcher matcher = COMMAND.matcher(line);
        if (!matcher.find()) return "";
        String command = matcher.group(1).replace(" ", "").toUpperCase(Locale.US);
        if (("M104".equals(command) || "M140".equals(command))
                && ZERO_PARAMETER.matcher(line).find()) return command + " S0";
        if ("M204".equals(command) && SAFE_DEFAULT_ACCELERATION.matcher(line).matches()) return "M204 S";
        if ("G92".equals(command) && G92_E_ZERO.matcher(line).find()) return "G92 E0";
        return command;
    }

    /** Require the reviewed native baseline before a result can leave staging. */
    public static void requireSafe(String gcode) throws IOException {
        Report report = inspect(gcode);
        if (!report.isValid()) throw new IOException("A1 Mini template preflight failed: " + report.summary());
    }

    private static String commandText(String raw) {
        if (raw == null) return "";
        int comment = raw.indexOf(';');
        return (comment < 0 ? raw : raw.substring(0, comment)).trim();
    }

    public static final class Report {
        public final ArrayList<String> errors;
        public final boolean hasStartMarker;
        public final boolean hasEndMarker;
        public final boolean hasUnresolvedTokens;

        private Report(ArrayList<String> errors, boolean hasStartMarker, boolean hasEndMarker,
                       boolean hasUnresolvedTokens) {
            this.errors = errors;
            this.hasStartMarker = hasStartMarker;
            this.hasEndMarker = hasEndMarker;
            this.hasUnresolvedTokens = hasUnresolvedTokens;
        }

        public boolean isValid() { return errors.isEmpty(); }

        public String summary() {
            return errors.isEmpty() ? "A1 Mini template policy passed" : errors.get(0);
        }
    }
}
