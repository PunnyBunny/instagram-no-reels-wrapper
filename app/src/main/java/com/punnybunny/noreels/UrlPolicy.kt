package com.punnybunny.noreels

import java.net.URI
import java.net.URLDecoder

/**
 * Decides what the app does with a navigation. Instagram pages are allowed only if they
 * belong to DMs, stories or the login/account flows; everything else on instagram.com
 * (reels, explore, posts, profiles) is blocked, and non-Instagram links go to the browser.
 *
 * Pure JVM code (java.net.URI, not android.net.Uri) so it runs in plain unit tests.
 */
object UrlPolicy {

    const val INBOX_URL = "https://www.instagram.com/direct/inbox/"
    const val STORIES_URL = "https://www.instagram.com/"

    /** Hosts that serve the Instagram web app itself. */
    val APP_HOSTS = listOf("instagram.com", "www.instagram.com", "m.instagram.com")

    /** Other Instagram hosts used during login / account management. */
    private val AUTH_HOSTS = setOf("i.instagram.com", "accountscenter.instagram.com")

    /** Path prefixes (with trailing slash) allowed on [APP_HOSTS]. "/" itself is the stories tray. */
    val ALLOWED_PATH_PREFIXES = listOf(
        "/direct/",
        "/stories/",
        "/accounts/",
        "/challenge/",
        "/two_factor/",
        "/auth_platform/",
        "/oauth/",
        "/session/",
        "/legal/",
        "/privacy/",
        "/api/",
        "/ajax/",
    )

    private val FACEBOOK_AUTH_PATH = Regex("^/(login|checkpoint|recover|auth|(v[0-9.]+/)?dialog/oauth)(/|$|\\.php)")

    private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "geo")

    sealed interface Decision {
        /** Load it in the WebView. */
        data object Allow : Decision

        /** Refuse to show it; [what] names the blocked content for the user. */
        data class Block(val what: String) : Decision

        /** Hand it to another app (browser, dialer, ...). */
        data class OpenExternally(val url: String) : Decision

        /** Silently ignore (e.g. "open in the Instagram app" deep links). */
        data object Drop : Decision
    }

    fun decide(url: String): Decision {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return Decision.Drop
        }
        val scheme = uri.scheme?.lowercase() ?: return Decision.Drop
        if (scheme in EXTERNAL_SCHEMES) return Decision.OpenExternally(url)
        if (scheme != "http" && scheme != "https") return Decision.Drop

        val host = uri.host?.lowercase() ?: return Decision.Drop
        val path = normalizePath(uri.rawPath)

        return when {
            host in APP_HOSTS ->
                if (isAllowedPath(path)) Decision.Allow else Decision.Block(describe(path))

            host == "l.instagram.com" -> unwrapLinkShim(uri)

            host in AUTH_HOSTS -> Decision.Allow

            isFacebook(host) && FACEBOOK_AUTH_PATH.containsMatchIn(path) -> Decision.Allow

            else -> Decision.OpenExternally(url)
        }
    }

    fun isAllowedPath(normalizedPath: String): Boolean =
        normalizedPath == "/" || ALLOWED_PATH_PREFIXES.any { normalizedPath.startsWith(it) }

    /** True when [url] is a DM page, i.e. the Messages tab should be highlighted. */
    fun isInbox(url: String): Boolean = pathOf(url)?.startsWith("/direct/") == true

    /** True for the inbox (the list of chats) itself, not a thread inside it. */
    fun isInboxList(url: String): Boolean = pathOf(url) == "/direct/inbox/"

    private fun pathOf(url: String): String? = try {
        normalizePath(URI(url).rawPath)
    } catch (e: Exception) {
        null
    }

    private fun normalizePath(rawPath: String?): String {
        val p = if (rawPath.isNullOrEmpty()) "/" else rawPath.lowercase()
        return if (p.endsWith("/")) p else "$p/"
    }

    private fun describe(path: String): String = when {
        path.startsWith("/reels/") || path.startsWith("/reel/") -> "Reels"
        path.startsWith("/explore/") -> "Explore"
        path.startsWith("/p/") -> "Posts"
        path.startsWith("/tv/") -> "Videos"
        else -> "This page"
    }

    private fun isFacebook(host: String) = host == "facebook.com" || host.endsWith(".facebook.com")

    /** l.instagram.com/?u=<target>&e=... wraps outbound links; open the real target instead. */
    private fun unwrapLinkShim(uri: URI): Decision {
        val target = uri.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("u=") }
            ?.removePrefix("u=")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?: return Decision.Drop
        return when (val inner = decide(target)) {
            is Decision.OpenExternally -> inner
            else -> Decision.Drop
        }
    }
}
