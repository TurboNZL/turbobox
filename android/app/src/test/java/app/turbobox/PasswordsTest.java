package app.turbobox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PasswordsTest {
    @Test
    public void rightPasswordOpensWrongOnesDoNot() {
        String[] h = Passwords.create("correct horse");
        assertTrue(Passwords.verify("correct horse", h[0], h[1]));
        assertFalse(Passwords.verify("correct horsE", h[0], h[1]));
        assertFalse(Passwords.verify("", h[0], h[1]));
        assertFalse(Passwords.verify("x", "", ""));
        assertFalse(Passwords.verify("x", "!!not base64", "??"));
    }

    @Test
    public void everyPasswordGetsItsOwnSalt() {
        String[] a = Passwords.create("same");
        String[] b = Passwords.create("same");
        assertNotEquals(a[0], b[0]);
        assertNotEquals(a[1], b[1]);
    }
}
