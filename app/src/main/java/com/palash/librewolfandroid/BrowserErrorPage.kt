package com.palash.librewolfandroid

import android.util.Base64
import org.mozilla.geckoview.WebRequestError

/** Generates local, non-network error pages for failed top-level navigations. */
object BrowserErrorPage {
    fun dataUri(url: String?, error: WebRequestError): String {
        val safeUrl = url.orEmpty()
        val details = when (error.code) {
            WebRequestError.ERROR_OFFLINE -> "You appear to be offline. Check your network connection and try again."
            WebRequestError.ERROR_UNKNOWN_HOST -> "The server address could not be found. Check the spelling or your DNS connection."
            WebRequestError.ERROR_CONNECTION_REFUSED -> "The server refused the connection."
            WebRequestError.ERROR_NET_TIMEOUT -> "The server took too long to respond."
            WebRequestError.ERROR_NET_RESET -> "The connection was reset before the page finished loading."
            WebRequestError.ERROR_NET_INTERRUPT -> "The network connection was interrupted."
            WebRequestError.ERROR_REDIRECT_LOOP -> "The website sent too many redirects."
            WebRequestError.ERROR_PORT_BLOCKED -> "Access to this network port is blocked."
            WebRequestError.ERROR_SECURITY_SSL,
            WebRequestError.ERROR_SECURITY_BAD_CERT,
            WebRequestError.ERROR_BAD_HSTS_CERT,
            -> "A secure connection could not be established. LibreWolf will not bypass certificate validation."
            WebRequestError.ERROR_HTTPS_ONLY -> "This page is not available over a secure HTTPS connection."
            WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI,
            WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI,
            -> "LibreWolf blocked this page because it was classified as malicious."
            WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI,
            WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI,
            -> "LibreWolf blocked this page because it was classified as deceptive or unwanted."
            WebRequestError.ERROR_PROXY_CONNECTION_REFUSED,
            WebRequestError.ERROR_UNKNOWN_PROXY_HOST,
            -> "The configured proxy could not be reached."
            WebRequestError.ERROR_MALFORMED_URI,
            WebRequestError.ERROR_UNKNOWN_PROTOCOL,
            -> "The address is invalid or uses an unsupported protocol."
            WebRequestError.ERROR_FILE_NOT_FOUND,
            WebRequestError.ERROR_FILE_ACCESS_DENIED,
            -> "The requested local file could not be opened."
            else -> "The page could not be loaded (error ${error.code})."
        }
        val title = when (error.category) {
            WebRequestError.ERROR_CATEGORY_SECURITY -> "Secure connection failed"
            WebRequestError.ERROR_CATEGORY_NETWORK -> "Website unavailable"
            WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> "Website blocked"
            else -> "Unable to load website"
        }
        val escapedUrl = android.text.Html.escapeHtml(safeUrl)
        val html = """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="color-scheme" content="dark light">
            <style>
            :root{color-scheme:dark}body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0b0b0e;color:#f7f7fa;font:16px system-ui,-apple-system,sans-serif}.card{width:min(34rem,86vw);padding:2rem;text-align:center;background:#18181d;border:1px solid #303038;border-radius:1.25rem;box-sizing:border-box}h1{font-size:1.45rem;margin:0 0 .8rem}.icon{width:4rem;height:4rem;margin:0 auto 1rem;border:3px solid #00ace8;border-radius:50%}p{color:#b8b8c0;line-height:1.5;overflow-wrap:anywhere}.url{font-size:.85rem;color:#85858f}.button{display:inline-block;margin-top:1.25rem;border-radius:999px;padding:.8rem 1.4rem;background:#00ace8;color:#001018;font-weight:700;text-decoration:none}
            </style></head><body><main class="card"><div class="icon"></div><h1>$title</h1><p>$details</p><p class="url">$escapedUrl</p><a class="button" href="$escapedUrl">Try again</a></main></body></html>
        """.trimIndent()
        val encoded = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "data:text/html;charset=utf-8;base64,$encoded"
    }
}
