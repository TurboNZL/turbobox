package app.turbobox;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Salted, slow hashes for profile passwords. Only the hash is stored, never the password. No Android classes. */
final class Passwords {
    private static final int ITERATIONS = 60_000;

    private Passwords() {
    }

    /** {salt, hash}, both Base64. */
    static String[] create(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return new String[]{Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(derive(password, salt))};
    }

    static boolean verify(String password, String salt, String hash) {
        if (salt == null || hash == null || salt.isEmpty() || hash.isEmpty() || password == null) return false;
        byte[] expected;
        byte[] actual;
        try {
            expected = Base64.getDecoder().decode(hash);
            actual = derive(password, Base64.getDecoder().decode(salt));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return java.security.MessageDigest.isEqual(expected, actual);   // constant time
    }

    private static byte[] derive(String password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
