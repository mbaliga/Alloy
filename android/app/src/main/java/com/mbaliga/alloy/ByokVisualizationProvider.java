package com.mbaliga.alloy;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * BYOK image-edit adapter for an OpenAI-compatible HTTPS endpoint.
 * The reference is a bounded PNG thumbnail sent as multipart form data; the
 * original STL/OBJ/3MF and printer credentials never leave the device.
 */
public final class ByokVisualizationProvider implements VisualizationProvider {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private final VisualizationCredentialStore.Credentials credentials;

    public ByokVisualizationProvider(VisualizationCredentialStore.Credentials credentials) {
        if (credentials == null) throw new IllegalArgumentException("BYOK credentials are required");
        this.credentials = credentials;
    }

    @Override public Result generate(Request request) throws IOException {
        if (request == null) throw new IOException("Visualization request is missing");
        HttpURLConnection connection = null;
        String boundary = "----AlloyVisualization" + Long.toHexString(System.nanoTime());
        try {
            URL url = new URL(credentials.endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(90_000);
            // Never let the platform follow a provider redirect to HTTP (or
            // to an unreviewed host) with the bearer key and rendered model
            // reference still attached. A BYOK endpoint must be explicit.
            disableRedirects(connection);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + credentials.apiKey);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream output = connection.getOutputStream()) {
                part(output, boundary, "model", null, credentials.model.getBytes(StandardCharsets.UTF_8));
                part(output, boundary, "prompt", null, request.prompt.getBytes(StandardCharsets.UTF_8));
                part(output, boundary, "size", null, "1024x1024".getBytes(StandardCharsets.UTF_8));
                part(output, boundary, "n", null, "1".getBytes(StandardCharsets.UTF_8));
                part(output, boundary, "response_format", null, "b64_json".getBytes(StandardCharsets.UTF_8));
                part(output, boundary, "image", "alloy-reference.png", request.referencePng);
                output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            }
            int code = connection.getResponseCode();
            InputStream body = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String payload = readText(body, MAX_RESPONSE_BYTES);
            if (code < 200 || code >= 300) throw new IOException("Visualization endpoint returned HTTP " + code + ": " + compact(payload));
            return parse(payload);
        } catch (java.net.MalformedURLException error) {
            throw new IOException("Visualization endpoint is invalid", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void part(OutputStream output, String boundary, String name, String fileName, byte[] value) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
        if (fileName == null) output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        else output.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(value); output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static Result parse(String payload) throws IOException {
        try {
            JSONObject root = new JSONObject(payload);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) throw new IOException("Visualization response contains no image");
            JSONObject image = data.optJSONObject(0);
            if (image == null) throw new IOException("Visualization response image is invalid");
            String encoded = image.optString("b64_json", "");
            if (encoded.length() > MAX_RESPONSE_BYTES) throw new IOException("Visualization base64 payload is too large");
            if (encoded.length() > 0) {
                byte[] bytes;
                try { bytes = Base64.decode(encoded, Base64.DEFAULT); }
                catch (IllegalArgumentException error) { throw new IOException("Visualization base64 image is invalid", error); }
                if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("Visualization image is too large");
                return new Result(bytes, "BYOK image edit");
            }
            String remote = image.optString("url", "");
            if (remote.length() == 0) throw new IOException("Visualization response has no supported image data");
            return new Result(download(remote), "BYOK image URL");
        } catch (org.json.JSONException error) { throw new IOException("Visualization response is not valid JSON", error); }
    }

    private static byte[] download(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Visualization image URL must use HTTPS");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(60_000);
        disableRedirects(connection);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Visualization image URL returned HTTP " + code);
            return readBytes(connection.getInputStream(), MAX_IMAGE_BYTES);
        } finally { connection.disconnect(); }
    }

    private static String readText(InputStream input, int max) throws IOException {
        return new String(readBytes(input, max), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream input, int max) throws IOException {
        if (input == null) throw new IOException("Visualization endpoint returned no body");
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024]; int total = 0; int read;
            while ((read = source.read(buffer)) != -1) {
                if (read == 0) continue;
                if (read > max - total) throw new IOException("Visualization response exceeds its size limit");
                output.write(buffer, 0, read); total += read;
            }
            return output.toByteArray();
        }
    }

    /** Keep redirect behavior explicit at both the POST and image-download boundary. */
    static void disableRedirects(HttpURLConnection connection) {
        if (connection == null) throw new IllegalArgumentException("Visualization connection is missing");
        connection.setInstanceFollowRedirects(false);
    }

    private static String compact(String value) {
        if (value == null) return "no error body";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() > 300 ? normalized.substring(0, 300) : normalized;
    }
}
