package io.renova.web.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts organisations' API keys at rest (AES-256-GCM). The master key comes from {@code RENOVA_MASTER_KEY}
 * (base64, 32 bytes) or, failing that, from {@code master.key} in the data directory, created on first use and
 * readable only by the account the server runs as. Keep it out of backups of the data directory.
 */
@Component
public class SecretBox {

    private static final int NONCE_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SecretBox(@Value("${renova.data-dir}") Path dataDir, @Value("${RENOVA_MASTER_KEY:}") String fromEnvironment)
            throws IOException {
        byte[] bytes;
        if (!fromEnvironment.isBlank()) {
            bytes = Base64.getDecoder().decode(fromEnvironment.strip());
        } else {
            Path file = dataDir.toAbsolutePath().normalize().resolve("master.key");
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                byte[] fresh = new byte[32];
                random.nextBytes(fresh);
                Files.writeString(file, Base64.getEncoder().encodeToString(fresh));
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException e) {
                    // Not a POSIX file system: rely on the directory's permissions.
                }
            }
            bytes = Base64.getDecoder().decode(Files.readString(file).strip());
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("The Renova master key must be 32 bytes (base64)");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String seal(String plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt", e);
        }
    }

    public String open(String sealed) {
        try {
            byte[] in = Base64.getDecoder().decode(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, NONCE_BYTES));
            return new String(cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot decrypt a stored key: the master key may have changed", e);
        }
    }
}
