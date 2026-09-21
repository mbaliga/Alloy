package com.mbaliga.alloy;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only Bambu LAN discovery.
 *
 * Bambu Studio-compatible printers answer an SSDP discovery request with a
 * small HTTP-like announcement. Discovery only learns a host, serial and
 * model; it never learns an access code and never sends a printer command.
 * Pairing and certificate pinning remain separate explicit user actions.
 */
public final class BambuPrinterDiscovery {
    static final int LISTEN_PORT = 2021;
    static final int SSDP_PORT = 1900;
    static final String SSDP_GROUP = "239.255.255.250";
    static final String DEVICE_TYPE = "urn:bambulab-com:device:3dprinter:1";
    private static final int MAX_PACKET_BYTES = 16 * 1024;
    private static final int READ_TIMEOUT_MS = 250;
    private static final long DEFAULT_TIMEOUT_MS = 5_000L;
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "alloy-bambu-discovery");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });

    private BambuPrinterDiscovery() { }

    public interface Callback {
        void onComplete(List<Printer> printers);
        void onError(Exception error);
    }

    /** A cancellable scan handle; closing it releases the Wi-Fi multicast lock. */
    public static final class Scan implements Closeable {
        private volatile MulticastSocket socket;
        private volatile WifiManager.MulticastLock multicastLock;
        private volatile boolean closed;

        private Scan() { }

        @Override public void close() {
            closed = true;
            MulticastSocket currentSocket = socket;
            socket = null;
            if (currentSocket != null) {
                try { currentSocket.leaveGroup(InetAddress.getByName(SSDP_GROUP)); }
                catch (Exception ignored) { }
                try { currentSocket.close(); }
                catch (Exception ignored) { }
            }
            WifiManager.MulticastLock lock = multicastLock;
            multicastLock = null;
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    public static Scan discover(Context context, Callback callback) {
        return discover(context, DEFAULT_TIMEOUT_MS, callback);
    }

    public static Scan discover(Context context, long timeoutMs, Callback callback) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (callback == null) throw new IllegalArgumentException("discovery callback is required");
        if (timeoutMs < 500L || timeoutMs > 30_000L)
            throw new IllegalArgumentException("discovery timeout is invalid");

        Scan scan = new Scan();
        EXECUTOR.execute(() -> runScan(context.getApplicationContext(), timeoutMs, callback, scan));
        return scan;
    }

    private static void runScan(Context context, long timeoutMs, Callback callback, Scan scan) {
        Map<String, Printer> found = new LinkedHashMap<>();
        MulticastSocket socket = null;
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("alloy-bambu-discovery");
                lock.setReferenceCounted(false);
                lock.acquire();
                scan.multicastLock = lock;
            }

            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(LISTEN_PORT));
            socket.setSoTimeout(READ_TIMEOUT_MS);
            scan.socket = socket;
            InetAddress group = InetAddress.getByName(SSDP_GROUP);
            socket.joinGroup(group);
            sendSearch(socket, group);

            byte[] buffer = new byte[MAX_PACKET_BYTES];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!scan.closed && System.currentTimeMillis() < deadline) {
                try {
                    packet.setLength(buffer.length);
                    socket.receive(packet);
                    Printer printer = parseResponse(buffer, packet.getLength(),
                            packet.getAddress() == null ? "" : packet.getAddress().getHostAddress());
                    if (printer != null) found.put(printer.identityKey(), printer);
                } catch (java.net.SocketTimeoutException ignored) {
                    // Short reads make cancellation responsive while the scan is open.
                }
            }
            if (!scan.closed) callback.onComplete(Collections.unmodifiableList(new ArrayList<>(found.values())));
        } catch (Exception error) {
            if (!scan.closed) callback.onError(error instanceof Exception ? (Exception) error : new IOException(error));
        } finally {
            scan.close();
        }
    }

    private static void sendSearch(MulticastSocket socket, InetAddress group) throws IOException {
        byte[] request = buildSearchRequest().getBytes(StandardCharsets.US_ASCII);
        socket.send(new DatagramPacket(request, request.length, group, SSDP_PORT));
    }

    /** Package-visible for protocol tests and documentation tooling. */
    static String buildSearchRequest() {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_GROUP + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + DEVICE_TYPE + "\r\n\r\n";
    }

    /** Parse one bounded SSDP response without performing any network I/O. */
    static Printer parseResponse(byte[] packet, int length, String sourceHost) throws IOException {
        if (packet == null || length <= 0 || length > MAX_PACKET_BYTES || length > packet.length)
            throw new IOException("discovery packet is invalid");
        String payload = new String(packet, 0, length, StandardCharsets.US_ASCII);
        return parseResponse(payload, sourceHost);
    }

    /** Package-visible for deterministic parser tests. */
    static Printer parseResponse(String payload, String sourceHost) throws IOException {
        if (payload == null || payload.length() == 0 || payload.length() > MAX_PACKET_BYTES)
            throw new IOException("discovery response is invalid");
        String[] lines = payload.split("\\r?\\n", -1);
        if (lines.length == 0 || !"HTTP/1.1 200 OK".equalsIgnoreCase(lines[0].trim())) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.length() == 0) break;
            int separator = line.indexOf(':');
            if (separator <= 0 || separator > 512) continue;
            String key = line.substring(0, separator).trim().toLowerCase(Locale.US);
            String value = line.substring(separator + 1).trim();
            if (key.length() > 128 || value.length() > 1024) continue;
            headers.put(key, value);
        }
        if (!DEVICE_TYPE.equalsIgnoreCase(headers.get("st"))) return null;

        String serial = safeToken(headers.get("usn"), 128);
        String model = safeToken(headers.get("devmodel.bambu.com"), 64);
        if (serial.length() == 0 || model.length() == 0) return null;
        String host = hostFromLocation(headers.get("location"), sourceHost);
        if (host.length() == 0) return null;
        String name = safeText(headers.get("devname.bambu.com"), 120);
        if (name.length() == 0) name = modelName(model);
        String signal = safeText(headers.get("devsignal.bambu.com"), 16);
        String connect = safeText(headers.get("devconnect.bambu.com"), 16).toLowerCase(Locale.US);
        String bind = safeText(headers.get("devbind.bambu.com"), 16).toLowerCase(Locale.US);
        return new Printer(host, serial, model, name, signal, connect, bind);
    }

    private static String hostFromLocation(String location, String sourceHost) {
        String candidate = location == null ? "" : location.trim();
        if (candidate.length() > 512) return "";
        if (candidate.length() > 0) {
            try {
                URI uri = new URI(candidate.contains("://") ? candidate : "http://" + candidate);
                if (uri.getHost() != null) candidate = uri.getHost();
            } catch (URISyntaxException ignored) {
                return "";
            }
        }
        if (candidate.length() == 0) candidate = sourceHost == null ? "" : sourceHost.trim();
        return PrinterHostValidator.tryNormalize(candidate);
    }

    private static String safeToken(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength && normalized.matches("[A-Za-z0-9._:-]{1," + maxLength + "}")
                ? normalized : "";
    }

    private static String safeText(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) return "";
        for (int index = 0; index < normalized.length(); index++)
            if (Character.isISOControl(normalized.charAt(index))) return "";
        return normalized;
    }

    private static String modelName(String model) {
        if ("N1".equalsIgnoreCase(model)) return "A1 Mini";
        if ("N2S".equalsIgnoreCase(model)) return "A1";
        return "Bambu printer";
    }

    public static final class Printer {
        public final String host;
        public final String serial;
        public final String model;
        public final String name;
        public final String signal;
        public final String connectMode;
        public final String bindMode;

        private Printer(String host, String serial, String model, String name,
                        String signal, String connectMode, String bindMode) {
            this.host = host;
            this.serial = serial;
            this.model = model;
            this.name = name;
            this.signal = signal;
            this.connectMode = connectMode;
            this.bindMode = bindMode;
        }

        public boolean isLanMode() { return "lan".equals(connectMode); }
        public boolean isA1Mini() { return "N1".equalsIgnoreCase(model); }
        public String identityKey() { return serial + "@" + host; }
        public String summary() {
            return name + " · " + model + " · " + host + (isLanMode() ? " · LAN" : " · not LAN");
        }
    }
}
