package app.turbobox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RootDomainTest {
    @Test
    public void siteRoots() {
        assertEquals("example.com", Hosts.rootDomain("a.b.example.com"));
        assertEquals("example.com", Hosts.rootDomain("example.com"));
        assertEquals("localhost", Hosts.rootDomain("localhost"));
        assertEquals("example.co.nz", Hosts.rootDomain("meet.example.co.nz"));
        assertEquals("cloud.microsoft", Hosts.rootDomain("teams.cloud.microsoft"));
        assertEquals("example.com.au", Hosts.rootDomain("www.example.com.au"));
    }
}
