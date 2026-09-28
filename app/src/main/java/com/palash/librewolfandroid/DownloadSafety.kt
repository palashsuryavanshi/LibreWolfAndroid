package com.palash.librewolfandroid

import java.util.Locale

/**
 * Download safety checks.
 *
 * GeckoView exposes no Safe Browsing verdict for a download — the API surface
 * only reports blocks on requests the engine made — so a file the browser is
 * about to fetch cannot be checked against a threat list from here. What the
 * shell *can* do is refuse to fetch a file silently that is either an executable
 * or an unencrypted transfer while HTTPS-only mode is on, and make the user
 * decide. The engine's own Safe Browsing still blocks malicious pages and
 * requests before they reach a download.
 */
object DownloadSafety {

    enum class Risk { EXECUTABLE, INSECURE }

    /** Installers, binaries and scripts: running one is how a device gets owned. */
    private val EXECUTABLE_EXTENSIONS = setOf(
        "apk", "exe", "msi", "dmg", "pkg", "deb", "rpm", "appimage", "app",
        "bat", "cmd", "com", "scr", "pif", "vbs", "vbe", "js", "jse", "wsf",
        "hta", "ps1", "sh", "bash", "zsh", "run", "bin", "elf", "dylib", "so",
        "jar", "reg", "lnk", "cpl", "sys",
    )

    private val EXECUTABLE_MIME_TYPES = setOf(
        "application/vnd.android.package-archive",
        "application/x-msdownload",
        "application/x-msdos-program",
        "application/x-dosexec",
        "application/x-executable",
        "application/x-sharedlib",
        "application/x-elf",
        "application/x-sh",
        "application/x-csh",
        "application/java-archive",
        "application/x-debian-package",
        "application/x-rpm",
        "application/x-ms-shortcut",
        "application/x-msi",
        "application/x-apple-diskimage",
    )

    /** A double extension is the oldest trick there is: report.pdf.exe. */
    private val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv",
        "jpg", "jpeg", "png", "gif", "webp", "mp3", "mp4", "mkv", "zip", "rar", "7z",
    )

    data class Verdict(val risk: Risk, val detail: String)

    /**
     * The name the server asked for, from `Content-Disposition`.
     *
     * This has to win over a name derived from the MIME type. A server sending
     * `Content-Type: application/pdf` with `filename="invoice.pdf.exe"` is the
     * oldest trick in the book, and `URLUtil.guessFileName` resolves that pair to
     * `invoice.pdf` — which would hide the executable extension from every check
     * here and from the name the file is actually saved under.
     */
    fun fileNameFrom(contentDisposition: String?, url: String, mimeType: String?): String {
        val fromHeader = contentDisposition?.let { disposition ->
            val encoded = Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)
            val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)
            (encoded?.groupValues?.get(1) ?: plain?.groupValues?.get(1))
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        }
        val raw = fromHeader ?: java.io.File(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)).name
        return sanitise(raw)
    }

    private fun sanitise(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it.isLetterOrDigit() || it in "._- ()[]" }.trim().trim('.')
        return cleaned.take(120).ifBlank { "download" }
    }

    fun inspect(url: String, fileName: String, mimeType: String?, httpsOnly: Boolean): Verdict? {
        val name = fileName.lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "")
        val mime = mimeType?.lowercase(Locale.ROOT)?.trim().orEmpty()
        val scheme = url.trimStart().lowercase(Locale.ROOT).substringBefore(':')

        if (extension.isNotEmpty() && extension in EXECUTABLE_EXTENSIONS) {
            return Verdict(Risk.EXECUTABLE, "file type .$extension")
        }
        if (mime in EXECUTABLE_MIME_TYPES) {
            return Verdict(Risk.EXECUTABLE, mime)
        }
        if (extension.isNotEmpty() && extension in DOCUMENT_EXTENSIONS) {
            val parts = name.split('.')
            if (parts.size >= 3 && parts[parts.size - 2] in EXECUTABLE_EXTENSIONS) {
                return Verdict(Risk.EXECUTABLE, "double extension .${parts.last()}")
            }
        }
        if (httpsOnly && scheme == "http") {
            return Verdict(Risk.INSECURE, "http://")
        }
        return null
    }
}
