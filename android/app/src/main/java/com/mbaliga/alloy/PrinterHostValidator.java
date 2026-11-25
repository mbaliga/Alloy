package com.mbaliga.alloy;

import java.util.Locale;

/**
 * Canonicalizes the host portion used by the Bambu LAN transport.
 *
 * A printer target is a host, not a URL, authority, port or path. Keeping this
 * rule in one place prevents discovery, pairing and the durable credential
 * record from disagreeing about what will be handed to a socket constructor.
 * IPv6 zone identifiers are accepted for local-link addresses, but brackets
 * are presentation syntax and are removed before persistence.
 */
public final class PrinterHostValidator {
    private static final int MAX_HOST_LENGTH = 253;

    private PrinterHostValidator() { }

    public static String require(String value) {
        if (value == null) throw new IllegalArgumentException("printer host is required");
        String host = value.trim();
        if (host.length() == 0) throw new IllegalArgumentException("printer host is required");
        if (host.length() > MAX_HOST_LENGTH)
            throw new IllegalArgumentException("printer host is too long");
        boolean bracketed = host.startsWith("[") || host.endsWith("]");
        if (bracketed) {
            if (!(host.startsWith("[") && host.endsWith("]")) || host.length() <= 2)
                throw new IllegalArgumentException("printer host brackets are invalid");
            host = host.substring(1, host.length() - 1);
        }
        if (bracketed && !isIpv6(host))
            throw new IllegalArgumentException("only IPv6 literals may use brackets");
        if (host.length() == 0 || host.indexOf('/') >= 0 || host.indexOf('\\') >= 0
                || host.indexOf('?') >= 0 || host.indexOf('#') >= 0 || host.indexOf('@') >= 0
                || host.indexOf(':') >= 0 && !isIpv6(host))
            throw new IllegalArgumentException("printer host must be an IPv4, IPv6 or DNS/mDNS host");
        if (isIpv4Shape(host) && !isIpv4(host))
            throw new IllegalArgumentException("printer IPv4 address is invalid");
        if (!isIpv4(host) && !isIpv6(host) && !isHostname(host))
            throw new IllegalArgumentException("printer host must be an IPv4, IPv6 or DNS/mDNS host");
        return host.toLowerCase(Locale.US);
    }

    /** Return an empty string instead of throwing while parsing untrusted discovery data. */
    static String tryNormalize(String value) {
        try { return require(value); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private static boolean isIpv4Shape(String value) {
        if (value == null || value.length() == 0) return false;
        int dots = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') dots++;
            else if (c < '0' || c > '9') return false;
        }
        return dots == 3;
    }

    private static boolean isIpv4(String value) {
        if (!isIpv4Shape(value)) return false;
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.length() == 0 || octet.length() > 3) return false;
            int number = 0;
            for (int i = 0; i < octet.length(); i++) number = number * 10 + octet.charAt(i) - '0';
            if (number > 255) return false;
        }
        return true;
    }

    private static boolean isIpv6(String value) {
        if (value == null || value.length() == 0 || value.indexOf(':') < 0) return false;
        int zone = value.indexOf('%');
        if (zone >= 0) {
            if (zone == 0 || zone == value.length() - 1 || value.indexOf('%', zone + 1) >= 0)
                return false;
            String zoneId = value.substring(zone + 1);
            if (!zoneId.matches("[A-Za-z0-9._-]{1,32}")) return false;
            value = value.substring(0, zone);
        }
        String[] compressed = value.split("::", -1);
        if (compressed.length > 2) return false;
        int groups = countIpv6Groups(compressed[0]);
        if (groups < 0) return false;
        if (compressed.length == 2) {
            int right = countIpv6Groups(compressed[1]);
            if (right < 0 || groups + right >= 8) return false;
            return true;
        }
        return groups == 8;
    }

    private static int countIpv6Groups(String side) {
        if (side.length() == 0) return 0;
        String[] groups = side.split(":", -1);
        for (String group : groups) {
            if (group.length() == 0 || group.length() > 4 || !group.matches("[0-9A-Fa-f]+")) return -1;
        }
        return groups.length;
    }

    private static boolean isHostname(String value) {
        if (value.length() > MAX_HOST_LENGTH || value.startsWith(".") || value.endsWith(".")
                || value.contains("..")) return false;
        String[] labels = value.split("\\.", -1);
        if (labels.length == 0) return false;
        for (String label : labels) {
            if (label.length() == 0 || label.length() > 63
                    || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) return false;
        }
        return true;
    }
}
