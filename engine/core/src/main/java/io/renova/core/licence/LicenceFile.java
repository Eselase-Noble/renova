package io.renova.core.licence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The licence file: the licence as JSON, and an Ed25519 signature over exactly those bytes.
 *
 * <pre>{ "payload": "base64 of the licence JSON", "signature": "base64" }</pre>
 *
 * Only the vendor's private key can produce a signature the public key shipped with Renova accepts, so a
 * licence cannot be edited or made up; it can be copied, which the seat count in it is there to discourage.
 */
public final class LicenceFile {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALGORITHM = "Ed25519";

    private LicenceFile() {
    }

    /** Reads a licence file and checks its signature; throws {@link LicenceException} if it is not genuine. */
    public static Licence read(String file, PublicKey vendor) {
        try {
            JsonNode root = JSON.readTree(file);
            byte[] payload = Base64.getDecoder().decode(root.path("payload").asText());
            byte[] signature = Base64.getDecoder().decode(root.path("signature").asText());
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(vendor);
            verifier.update(payload);
            if (payload.length == 0 || !verifier.verify(signature)) {
                throw new LicenceException("This licence file was not issued by Renova's vendor, or was changed after it was issued.");
            }
            JsonNode l = JSON.readTree(payload);
            List<String> ecosystems = new ArrayList<>();
            l.path("ecosystems").forEach(e -> ecosystems.add(e.asText()));
            return new Licence(l.path("id").asText(), l.path("licensee").asText(), l.path("edition").asText("standard"), ecosystems,
                    l.path("seats").asInt(1), date(l, "issued"), date(l, "expires"));
        } catch (LicenceException e) {
            throw e;
        } catch (Exception e) {
            throw new LicenceException("This is not a Renova licence file (" + e.getMessage() + ").");
        }
    }

    /** Writes the file for a licence, signed with the vendor's private key. */
    public static String write(Licence licence, PrivateKey vendor) {
        try {
            ObjectNode l = JSON.createObjectNode();
            l.put("id", licence.id());
            l.put("licensee", licence.licensee());
            l.put("edition", licence.edition());
            licence.ecosystems().forEach(l.putArray("ecosystems")::add);
            l.put("seats", licence.seats());
            l.put("issued", String.valueOf(licence.issued()));
            l.put("expires", String.valueOf(licence.expires()));
            byte[] payload = JSON.writeValueAsBytes(l);
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(vendor);
            signer.update(payload);
            ObjectNode root = JSON.createObjectNode();
            root.put("payload", Base64.getEncoder().encodeToString(payload));
            root.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Cannot sign the licence: " + e.getMessage(), e);
        }
    }

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A key as one line of base64, the form it is kept in. */
    public static String encode(java.security.Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Not an Ed25519 public key: " + e.getMessage(), e);
        }
    }

    public static PrivateKey privateKey(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Not an Ed25519 private key: " + e.getMessage(), e);
        }
    }

    private static LocalDate date(JsonNode licence, String field) {
        return LocalDate.parse(licence.path(field).asText());
    }
}
