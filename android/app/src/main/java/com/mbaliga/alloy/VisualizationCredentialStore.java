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

/** Encrypts the user-owned visualization endpoint and BYOK key with Android Keystore. */
public final class VisualizationCredentialStore {
    private static final String KEY_ALIAS = "alloy.visualization.byok.v1";
    private static final String ENCRYPTED = "encrypted";
    private static final String IV = "iv";
    private final SharedPreferences preferences;

    public VisualizationCredentialStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("preferences are required");
        this.preferences = preferences;
    }

    public void save(Credentials credentials) throws GeneralSecurityException {
        if (credentials == null) throw new IllegalArgumentException("visualization credentials are required");
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        byte[] plaintext = serialize(credentials);
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            encrypted = cipher.doFinal(plaintext);
            if (!preferences.edit().putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
                throw new GeneralSecurityException("Could not persist visualization credentials");
        } finally {
            Arrays.fill(iv, (byte) 0); Arrays.fill(plaintext, (byte) 0);
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
            Arrays.fill(iv, (byte) 0); Arrays.fill(encrypted, (byte) 0);
        }
    }

    public void clear() {
        if (!preferences.edit().remove(IV).remove(ENCRYPTED).commit())
            throw new IllegalStateException("Could not clear visualization credentials");
    }

    private SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
            if (!store.containsAlias(KEY_ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
                generator.generateKey();
            }
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        } catch (Exception error) {
            if (error instanceof GeneralSecurityException) throw (GeneralSecurityException) error;
            throw new GeneralSecurityException("Could not access Android Keystore", error);
        }
    }

    private static byte[] serialize(Credentials credentials) throws GeneralSecurityException {
        try {
            return new JSONObject().put("endpoint", credentials.endpoint).put("model", credentials.model)
                    .put("api_key", credentials.apiKey).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) { throw new GeneralSecurityException("Could not encode visualization credentials", error); }
    }

    private static Credentials deserialize(byte[] payload) throws GeneralSecurityException {
        try {
            JSONObject value = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            return new Credentials(value.getString("endpoint"), value.getString("model"), value.getString("api_key"));
        } catch (Exception error) { throw new GeneralSecurityException("Could not decode visualization credentials", error); }
        finally { Arrays.fill(payload, (byte) 0); }
    }

    public static final class Credentials {
        public final String endpoint;
        public final String model;
        public final String apiKey;

        public Credentials(String endpoint, String model, String apiKey) {
            this.endpoint = endpoint(endpoint);
            this.model = text(model, "model", 120);
            this.apiKey = text(apiKey, "API key", 512);
        }

        private static String endpoint(String value) {
            String normalized = text(value, "endpoint", 512);
            try {
                java.net.URI uri = new java.net.URI(normalized);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
                    throw new IllegalArgumentException("Visualization endpoint must use HTTPS");
            } catch (java.net.URISyntaxException error) { throw new IllegalArgumentException("Visualization endpoint is invalid", error); }
            return normalized;
        }

        private static String text(String value, String label, int max) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.length() == 0 || normalized.length() > max) throw new IllegalArgumentException(label + " is invalid");
            for (int i = 0; i < normalized.length(); i++)
                if (Character.isISOControl(normalized.charAt(i))) throw new IllegalArgumentException(label + " contains control characters");
            return normalized;
        }
    }
}
