/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small Android-Keystore backed primitive used by container metadata.
 *
 * The ciphertext format is deliberately versioned and self-contained:
 * [version:1][ivLength:1][iv][AES-GCM ciphertext+tag]. Container identifiers
 * are supplied as authenticated data by callers and are never sent anywhere.
 */
public final class AgramSecureStore {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final byte FORMAT_VERSION = 1;

    private AgramSecureStore() {
    }

    public static byte[] encrypt(String scope, byte[] cleartext, byte[] associatedData) throws GeneralSecurityException {
        return encrypt(scope, cleartext, associatedData, true);
    }

    /** Existing ciphertext must never be rewritten under a replacement Keystore key. */
    public static byte[] encryptExisting(String scope, byte[] cleartext, byte[] associatedData) throws GeneralSecurityException {
        return encrypt(scope, cleartext, associatedData, false);
    }

    private static byte[] encrypt(String scope, byte[] cleartext, byte[] associatedData, boolean allowCreate) throws GeneralSecurityException {
        SecretKey key = allowCreate ? getOrCreateKey(scope) : getExistingKey(scope);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        if (associatedData != null) {
            cipher.updateAAD(associatedData);
        }
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(cleartext);
        ByteBuffer output = ByteBuffer.allocate(2 + iv.length + encrypted.length);
        output.put(FORMAT_VERSION);
        output.put((byte) iv.length);
        output.put(iv);
        output.put(encrypted);
        return output.array();
    }

    public static byte[] decrypt(String scope, byte[] encoded, byte[] associatedData) throws GeneralSecurityException {
        if (encoded == null || encoded.length < 2) {
            throw new GeneralSecurityException("Missing encrypted container data");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded);
        byte version = input.get();
        int ivLength = input.get() & 0xff;
        if (version != FORMAT_VERSION || ivLength < 12 || ivLength > 32 || input.remaining() <= ivLength) {
            throw new GeneralSecurityException("Unsupported encrypted container data");
        }
        byte[] iv = new byte[ivLength];
        input.get(iv);
        byte[] encrypted = new byte[input.remaining()];
        input.get(encrypted);

        Cipher cipher = Cipher.getInstance(CIPHER);
        // Decryption must never create a replacement key. If Android has lost
        // or invalidated the original alias, generating a new key would only
        // make the existing ciphertext look corrupt and could tempt callers to
        // overwrite an otherwise recoverable container mapping.
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(scope), new GCMParameterSpec(128, iv));
        if (associatedData != null) {
            cipher.updateAAD(associatedData);
        }
        return cipher.doFinal(encrypted);
    }

    public static void deleteKey(String scope) {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            String alias = aliasFor(scope);
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias);
            }
        } catch (Exception e) {
            FileLog.e("Unable to delete Agram container key", e);
        }
    }

    public static byte[] aad(String containerId, String purpose) {
        return ("agram:v1:" + purpose + ":" + containerId).getBytes(StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey(String scope) throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            String alias = aliasFor(scope);
            if (keyStore.containsAlias(alias)) {
                return (SecretKey) keyStore.getKey(alias, null);
            }
            try {
                return generateKey(alias, Build.VERSION.SDK_INT >= Build.VERSION_CODES.P);
            } catch (StrongBoxUnavailableException e) {
                return generateKey(alias, false);
            }
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Android Keystore is unavailable", e);
        }
    }

    private static SecretKey getExistingKey(String scope) throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            String alias = aliasFor(scope);
            if (!keyStore.containsAlias(alias)) {
                throw new KeyUnavailableException("Agram container key is missing");
            }
            Key key = keyStore.getKey(alias, null);
            if (!(key instanceof SecretKey)) {
                throw new KeyUnavailableException("Agram container key is invalid");
            }
            return (SecretKey) key;
        } catch (KeyUnavailableException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new KeyUnavailableException("Android Keystore key is unavailable", e);
        } catch (Exception e) {
            throw new KeyUnavailableException("Android Keystore key is unavailable", e);
        }
    }

    /** Signals a recoverable Keystore problem without exposing an alias. */
    public static final class KeyUnavailableException extends GeneralSecurityException {
        public KeyUnavailableException(String message) {
            super(message);
        }

        public KeyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static SecretKey generateKey(String alias, boolean strongBox) throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false);
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true);
        }
        generator.init(builder.build());
        return generator.generateKey();
    }

    private static String aliasFor(String scope) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(scope.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder("agram_container_");
        for (int i = 0; i < 16; i++) {
            value.append(String.format(Locale.US, "%02x", hash[i]));
        }
        return value.toString();
    }
}
