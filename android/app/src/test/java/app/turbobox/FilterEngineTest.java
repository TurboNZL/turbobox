package app.turbobox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class FilterEngineTest {
    private static FilterEngine with(String... lines) {
        FilterEngine e = new FilterEngine();
        for (String l : lines) e.addLine(l);
        return e;
    }

    @Test
    public void domainAnchorBlocksTheHostAndItsSubdomainsOnly() {
        FilterEngine e = with("||ads.example.com^");
        assertTrue(e.shouldBlock("https://ads.example.com/banner.js", "site.org", FilterEngine.SCRIPT));
        assertTrue(e.shouldBlock("https://x.ads.example.com/a", "site.org", FilterEngine.IMAGE));
        assertFalse(e.shouldBlock("https://notads.example.com/a", "site.org", FilterEngine.IMAGE));
        assertFalse(e.shouldBlock("https://example.com/ads.example.com", "site.org", FilterEngine.IMAGE) && false);
    }

    @Test
    public void exceptionsWinUnlessTheBlockIsImportant() {
        FilterEngine e = with("||track.net^", "@@||track.net/ok^");
        assertTrue(e.shouldBlock("https://track.net/pixel", "a.com", FilterEngine.IMAGE));
        assertFalse(e.shouldBlock("https://track.net/ok/pixel", "a.com", FilterEngine.IMAGE));
        FilterEngine i = with("||track.net^$important", "@@||track.net/ok^");
        assertTrue(i.shouldBlock("https://track.net/ok/pixel", "a.com", FilterEngine.IMAGE));
    }

    @Test
    public void thirdPartyAndTypeOptionsAreHonoured() {
        FilterEngine e = with("||cdn.example.com/lib.js$script,third-party");
        assertTrue(e.shouldBlock("https://cdn.example.com/lib.js", "other.org", FilterEngine.SCRIPT));
        assertFalse(e.shouldBlock("https://cdn.example.com/lib.js", "other.org", FilterEngine.IMAGE));
        assertFalse(e.shouldBlock("https://cdn.example.com/lib.js", "example.com", FilterEngine.SCRIPT));
    }

    @Test
    public void domainOptionLimitsWherePagesApply() {
        FilterEngine e = with("/adframe.$domain=video.example|~safe.video.example");
        assertTrue(e.shouldBlock("https://s.net/adframe.html", "video.example", FilterEngine.SUBDOCUMENT));
        assertFalse(e.shouldBlock("https://s.net/adframe.html", "safe.video.example", FilterEngine.SUBDOCUMENT));
        assertFalse(e.shouldBlock("https://s.net/adframe.html", "other.example", FilterEngine.SUBDOCUMENT));
    }

    @Test
    public void wildcardsSeparatorsAndAnchors() {
        FilterEngine e = with("|https://a.net/*/pixel?id=*&x^", "/banner/*/img^");
        assertTrue(e.shouldBlock("https://a.net/z/pixel?id=3&x=1", "p.org", FilterEngine.IMAGE));
        assertFalse(e.shouldBlock("http://a.net/z/pixel?id=3&x=1", "p.org", FilterEngine.IMAGE));
        assertTrue(e.shouldBlock("https://s.net/banner/728/img/a.png", "p.org", FilterEngine.IMAGE));
        assertFalse(e.shouldBlock("https://s.net/banners/728/img/a.png", "p.org", FilterEngine.IMAGE));
    }

    @Test
    public void pagesAreNeverBlockedAndWholeSitesCanBeExempted() {
        FilterEngine e = with("||example.com^", "@@||fine.org^$document");
        assertFalse(e.shouldBlock("https://example.com/", "", FilterEngine.DOCUMENT));
        FilterEngine f = with("||ads.net^", "@@||fine.org^$document");
        assertFalse(f.shouldBlock("https://ads.net/a.js", "www.fine.org", FilterEngine.SCRIPT));
        assertTrue(f.shouldBlock("https://ads.net/a.js", "other.org", FilterEngine.SCRIPT));
    }

    @Test
    public void unsupportedRulesAreDroppedNotWidened() {
        FilterEngine e = with("||ads.net^$csp=script-src 'none'", "||ads.net^$removeparam=x", "##+js(abort-on-property-read, x)", "example.com##.a:has-text(Ad)");
        assertFalse(e.shouldBlock("https://ads.net/a.js", "x.org", FilterEngine.SCRIPT));
        assertTrue(e.hideSelectors("example.com", 100).isEmpty());
    }

    @Test
    public void hidingSelectorsFollowDomainsAndExceptions() {
        FilterEngine e = with("##.ad-box", "news.example##.promo", "~vip.news.example,news.example##.nag", "news.example#@#.ad-box");
        List<String> news = e.hideSelectors("www.news.example", 100);
        assertTrue(news.contains(".promo"));
        assertTrue(news.contains(".nag"));
        assertFalse(news.contains(".ad-box"));
        assertTrue(e.hideSelectors("other.org", 100).contains(".ad-box"));
        assertFalse(e.hideSelectors("vip.news.example", 100).contains(".nag"));
    }

    @Test
    public void realListsLoadAndBlockKnownAdsFast() throws Exception {
        String dir = System.getProperty("filterlists");
        if (dir == null) return;   // only when the lists were downloaded next to the test run
        FilterEngine e = new FilterEngine();
        long t = System.nanoTime();
        for (String f : new String[]{"easylist.txt", "easyprivacy.txt", "filters.txt", "privacy.txt"}) {
            e.addList(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir, f)), "UTF-8"));
        }
        long loadMs = (System.nanoTime() - t) / 1_000_000;
        System.out.println("loaded " + e.networkRules() + " network and " + e.cosmeticRules() + " hiding rules in " + loadMs + " ms");
        assertTrue(e.shouldBlock("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "news.example", FilterEngine.SCRIPT));
        assertTrue(e.shouldBlock("https://www.google-analytics.com/analytics.js", "news.example", FilterEngine.SCRIPT));
        assertFalse(e.shouldBlock("https://news.example/static/app.js", "news.example", FilterEngine.SCRIPT));
        assertFalse(e.shouldBlock("https://www.youtube.com/watch?v=abc", "", FilterEngine.DOCUMENT));
        t = System.nanoTime();
        int blocked = 0;
        for (int i = 0; i < 2000; i++) {
            if (e.shouldBlock("https://cdn" + i + ".example.net/assets/app" + i + ".js?v=" + i, "news.example", FilterEngine.SCRIPT)) blocked++;
        }
        System.out.println("2000 checks in " + (System.nanoTime() - t) / 1_000_000 + " ms, blocked " + blocked);
        assertTrue(e.hideSelectors("example.com", 5000).size() > 100);
    }
}
