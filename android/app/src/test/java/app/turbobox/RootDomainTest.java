package app.turbobox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RootDomainTest {
    @Test
    public void siteRoots() {
        assertEquals("example.com", Session.rootDomain("a.b.example.com"));
        assertEquals("example.com", Session.rootDomain("example.com"));
        assertEquals("localhost", Session.rootDomain("localhost"));
        assertEquals("example.co.nz", Session.rootDomain("meet.example.co.nz"));
        assertEquals("cloud.microsoft", Session.rootDomain("teams.cloud.microsoft"));
        assertEquals("example.com.au", Session.rootDomain("www.example.com.au"));
    }
}
