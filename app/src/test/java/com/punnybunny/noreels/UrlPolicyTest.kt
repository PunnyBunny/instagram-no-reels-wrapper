package com.punnybunny.noreels

import com.punnybunny.noreels.UrlPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun allowsDmsStoriesAndHome() {
        listOf(
            "https://www.instagram.com/",
            "https://www.instagram.com",
            "https://instagram.com/?variant=following",
            "https://www.instagram.com/direct/inbox/",
            "https://www.instagram.com/direct/t/1234567890/",
            "https://www.instagram.com/direct",
            "https://www.instagram.com/stories/someone/3141592653/",
            "https://www.instagram.com/stories/highlights/1789/",
            "https://www.instagram.com/accounts/login/?next=%2Fdirect%2Finbox%2F",
            "https://www.instagram.com/challenge/?next=/",
            "https://i.instagram.com/api/v1/whatever",
            "https://accountscenter.instagram.com/",
            "https://m.facebook.com/login.php?next=x",
            "https://www.facebook.com/v19.0/dialog/oauth?client_id=1",
        ).forEach { assertEquals(it, Decision.Allow, UrlPolicy.decide(it)) }
    }

    @Test
    fun blocksReelsExploreFeedAndProfiles() {
        mapOf(
            "https://www.instagram.com/reels/" to "Reels",
            "https://www.instagram.com/reels/C0ffee/" to "Reels",
            "https://www.instagram.com/reel/C0ffee/?igsh=abc" to "Reels",
            "https://www.instagram.com/REEL/C0ffee" to "Reels",
            "https://instagram.com/explore/" to "Explore",
            "https://www.instagram.com/explore/search/" to "Explore",
            "https://www.instagram.com/p/Babc123/" to "Posts",
            "https://www.instagram.com/tv/Babc123/" to "Videos",
            "https://www.instagram.com/someuser/" to "This page",
            "https://www.instagram.com/directory/" to "This page",
            "https://www.instagram.com/storiesx/" to "This page",
        ).forEach { (url, what) -> assertEquals(url, Decision.Block(what), UrlPolicy.decide(url)) }
    }

    @Test
    fun sendsOtherSitesToTheBrowser() {
        val url = "https://example.com/article?id=1"
        assertEquals(Decision.OpenExternally(url), UrlPolicy.decide(url))
        assertEquals(
            Decision.OpenExternally("https://www.facebook.com/somepage"),
            UrlPolicy.decide("https://www.facebook.com/somepage"),
        )
        assertEquals(Decision.OpenExternally("mailto:a@b.c"), UrlPolicy.decide("mailto:a@b.c"))
    }

    @Test
    fun unwrapsInstagramLinkShim() {
        val shim = "https://l.instagram.com/?u=https%3A%2F%2Fexample.com%2Fx%3Fa%3D1&e=AT0abc"
        assertEquals(Decision.OpenExternally("https://example.com/x?a=1"), UrlPolicy.decide(shim))
        assertEquals(Decision.Drop, UrlPolicy.decide("https://l.instagram.com/?e=nothing"))
    }

    @Test
    fun dropsAppDeepLinksAndGarbage() {
        assertEquals(Decision.Drop, UrlPolicy.decide("instagram://reels_home"))
        assertEquals(Decision.Drop, UrlPolicy.decide("intent://reel/abc#Intent;scheme=instagram;end"))
        assertEquals(Decision.Drop, UrlPolicy.decide("not a url at all"))
    }

    @Test
    fun detectsInbox() {
        assertTrue(UrlPolicy.isInbox("https://www.instagram.com/direct/t/1/"))
        assertFalse(UrlPolicy.isInbox("https://www.instagram.com/"))
        assertFalse(UrlPolicy.isInbox("https://www.instagram.com/stories/a/1/"))
        assertTrue(UrlPolicy.isInboxList("https://www.instagram.com/direct/inbox/"))
        assertTrue(UrlPolicy.isInboxList("https://www.instagram.com/direct/inbox"))
        assertFalse(UrlPolicy.isInboxList("https://www.instagram.com/direct/t/1/"))
    }
}
