package com.mbaliga.alloy;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores LAN credentials encrypted with a non-exportable Android Keystore key. */
public final class PrinterCredentialStore {
    private static final String KEY_ALIAS = "alloy.printer.credentials.v1";
    private static final String ENCRYPTED = "encrypted";
    private static final String IV = "iv";

    private final SharedPreferences preferences;

    public PrinterCredentialStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public void save(Credentials credentials) throws GeneralSecurityException {
        if (credentials == null) throw new IllegalArgumentException("credentials are required");
        byte[] plaintext = serialize(credentials);
        byte[] iv = null;
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Android Keystore enforces randomized encryption for this key and
            // rejects a caller-supplied IV. Let the provider generate it, then
            // persist the exact IV that decrypt() will need.
            cipher.init(Cipher.ENCRYPT_MODE, key());
            iv = cipher.getIV();
            encrypted = cipher.doFinal(plaintext);
            if (!preferences.edit()
                    .putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit()) {
                throw new GeneralSecurityException("Could not persist encrypted printer credentials");
            }
        } finally {
            // Keystore/provider failures can occur before an IV or ciphertext
            // exists. Preserve the original security exception instead of
            // masking it with a cleanup NullPointerException.
            if (iv != null) Arrays.fill(iv, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    public Credentials load() throws GeneralSecurityException {
        String encodedIv = preferences.getString(IV, null);
        String encodedEncrypted = preferences.getString(ENCRYPTED, null);
        if (encodedIv == null || encodedEncrypted == null) return null;
        byte[] iv = Base64.decode(encodedIv, Base64.DEFAULT);
        byte[] encrypted = Base64.decode(encodedEncrypted, Base64.DEFAULT);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return deserialize(cipher.doFinal(encrypted));
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    public void clear() {
        if (!preferences.edit().remove(IV).remove(ENCRYPTED).commit())
            throw new IllegalStateException("Could not clear encrypted printer credentials");
    }

    private SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
            if (!store.containsAlias(KEY_ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
                generator.generateKey();
            }
            return ((SecretKey) store.getKey(KEY_ALIAS, null));
        } catch (Exception error) {
            if (error instanceof GeneralSecurityException) throw (GeneralSecurityException) error;
            throw new GeneralSecurityException("Could not access Android Keystore", error);
        }
    }

    private static byte[] serialize(Credentials credentials) throws GeneralSecurityException {
        try {
            return new JSONObject()
                    .put("name", credentials.name)
                    .put("host", credentials.host)
                    .put("serial", credentials.serial)
                    .put("model", credentials.model)
                    .put("access_code", credentials.accessCode)
                    .put("certificate_sha256", credentials.certificateFingerprint)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new GeneralSecurityException("Could not encode printer credentials", error);
        }
    }

    private static Credentials deserialize(byte[] payload) throws GeneralSecurityException {
        try {
            JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            return new Credentials(json.getString("name"), json.getString("host"), json.getString("serial"),
                    json.getString("access_code"), json.optString("certificate_sha256", ""),
                    json.optString("model", ""));
        } catch (Exception error) {
            throw new GeneralSecurityException("Could not decode printer credentials", error);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    public static final class Credentials {
        public final String name;
        public final String host;
        public final String serial;
        /** Bambu discovery model code, e.g. N1 for the A1 Mini. */
        public final String model;
        public final String accessCode;
        public final String certificateFingerprint;

        public Credentials(String name, String host, String serial, String accessCode) {
            this(name, host, serial, accessCode, "", "");
        }

        public Credentials(String name, String host, String serial, String accessCode, String certificateFingerprint) {
            this(name, host, serial, accessCode, certificateFingerprint, "");
        }

        public Credentials(String name, String host, String serial, String accessCode,
                           String certificateFingerprint, String model) {
            this.name = safeField(name, "printer name", 120, true);
            this.host = PrinterHostValidator.require(host);
            this.serial = topicToken(serial, "printer serial", 128);
            this.model = optionalSafeField(model, "printer model", 64);
            this.accessCode = safeField(accessCode, "access code", 128, true);
            this.certificateFingerprint = normalizeFingerprint(certificateFingerprint);
        }

        /** Physical upload/start requires an explicit pin for the printer's leaf certificate. */
        public boolean hasCertificatePin() {
            return certificateFingerprint.length() == 64;
        }

        /** Only the N1/A1 Mini target is compatible with Alloy's current profile. */
        public boolean isA1Mini() {
            String normalized = model.toLowerCase(java.util.Locale.US)
                    .replace(" ", "").replace("-", "").replace("_", "");
            return "n1".equals(normalized) || "a1mini".equals(normalized)
                    || "bambulaba1mini".equals(normalized);
        }

        private static String topicToken(String value, String label, int maxLength) {
            String normalized = safeField(value, label, maxLength, false);
            if (!normalized.matches("[A-Za-z0-9._:-]+"))
                throw new IllegalArgumentException(label + " contains unsupported topic characters");
            return normalized;
        }

        private static String safeField(String value, String label, int maxLength, boolean allowWhitespace) {
            String normalized = PrinterTransport.require(value, label).trim();
            if (normalized.length() > maxLength)
                throw new IllegalArgumentException(label + " is too long");
            for (int index = 0; index < normalized.length(); index++) {
                char character = normalized.charAt(index);
                if (Character.isISOControl(character) || (!allowWhitespace && Character.isWhitespace(character)))
                    throw new IllegalArgumentException(label + " contains unsupported control characters");
            }
            return normalized;
        }

        private static String optionalSafeField(String value, String label, int maxLength) {
            if (value == null || value.trim().length() == 0) return "";
            return safeField(value, label, maxLength, true);
        }

        private static String normalizeFingerprint(String value) {
            if (value == null || value.trim().length() == 0) return "";
            String normalized = value.replace(":", "").replace(" ", "").toLowerCase(java.util.Locale.US);
            if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Certificate fingerprint must be SHA-256");
            return normalized;
        }
    }
}
