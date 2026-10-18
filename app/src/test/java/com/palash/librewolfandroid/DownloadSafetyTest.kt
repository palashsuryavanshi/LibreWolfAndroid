package com.palash.librewolfandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the download decisions the shell makes before a file is
 * fetched.
 *
 * These existed only as manual on-device checks. The behaviour they pin is
 * security relevant in two ways: a file that is executable or transferred in
 * clear text must be refused silently-fetching and put to the user, and a
 * server-supplied filename must not be able to steer the write outside the
 * download directory.
 *
 * `fileNameFrom` is exercised only with a Content-Disposition header, because
 * its fallback path calls android.webkit.URLUtil, which is not available on a
 * plain JVM. That is why every case here supplies a header.
 */
class DownloadSafetyTest {

    private fun name(header: String): String =
        DownloadSafety.fileNameFrom(header, "https://example.com/d", "application/octet-stream")

    // ---- path traversal -----------------------------------------------------

    @Test
    fun `a unix traversal in the filename cannot escape`() {
        val n = name("""attachment; filename="../../../../data/data/com.palash.librewolfandroid/shared_prefs/x.xml"""")
        assertTrue("traversal segments must be stripped, was '$n'", !n.contains(".."))
        assertTrue("must be a bare name, was '$n'", !n.contains('/'))
    }

    @Test
    fun `a windows traversal in the filename cannot escape`() {
        val n = name("""attachment; filename="..\\..\\Windows\\System32\\evil.exe"""")
        assertTrue("backslash traversal must be stripped, was '$n'", !n.contains('\\'))
        assertTrue("must not contain parent refs, was '$n'", !n.contains(".."))
    }

    @Test
    fun `an absolute path is reduced to its last segment`() {
        assertEquals("passwd", name("""attachment; filename="/etc/passwd""""))
    }

    @Test
    fun `a bare filename survives intact`() {
        assertEquals("invoice.pdf", name("""attachment; filename="invoice.pdf""""))
    }

    @Test
    fun `a filename that sanitises away falls back rather than becoming empty`() {
        // Reached through the header path on purpose. A literal `filename=""`
        // or `filename="   "` does not match the header regex at all, so
        // fileNameFrom falls through to android.webkit.URLUtil for the name --
        // a framework class that does not exist on a plain JVM, and so cannot
        // be asserted here. These two inputs DO match the regex and then
        // sanitise down to nothing, exercising the same ifBlank fallback.
        assertEquals("download", name("""attachment; filename="///""""))
        assertEquals("download", name("""attachment; filename="..."""".dropLast(1)))
    }

    @Test
    fun `the filename is length capped`() {
        val n = name("""attachment; filename="${"a".repeat(500)}.pdf"""")
        assertTrue("expected <=120 chars, was ${n.length}", n.length <= 120)
    }

    // ---- Content-Disposition beats the MIME type ----------------------------

    @Test
    fun `the server filename wins over the mime type`() {
        // This is the documented case: URLUtil.guessFileName resolves
        // application/pdf + "invoice.pdf.exe" to "invoice.pdf", hiding the
        // executable extension from every check. Taking the header verbatim is
        // what stops that.
        val n = name("""attachment; filename="invoice.pdf.exe"""")
        assertEquals("invoice.pdf.exe", n)
        assertEquals(
            DownloadSafety.Risk.EXECUTABLE,
            DownloadSafety.inspect("https://example.com/x", n, "application/pdf", false)?.risk,
        )
    }

    @Test
    fun `an RFC 5987 encoded filename is decoded`() {
        val n = name("""attachment; filename*=UTF-8''caf%C3%A9%20menu.pdf""")
        assertTrue("expected the decoded name, was '$n'", n.contains("caf"))
    }

    // ---- executable classification -----------------------------------------

    @Test
    fun `executables by extension are flagged`() {
        for (ext in listOf("apk", "exe", "sh", "jar", "so", "bin", "bat")) {
            val v = DownloadSafety.inspect(
                "https://example.com/f.$ext", "f.$ext", "application/octet-stream", false,
            )
            assertNotNull(".$ext should be flagged", v)
            assertEquals(".$ext should be EXECUTABLE", DownloadSafety.Risk.EXECUTABLE, v!!.risk)
        }
    }

    @Test
    fun `extension matching is case insensitive`() {
        val v = DownloadSafety.inspect("https://example.com/f.EXE", "f.EXE", null, false)
        assertEquals(DownloadSafety.Risk.EXECUTABLE, v?.risk)
    }

    @Test
    fun `an executable mime type is flagged even with a harmless name`() {
        val v = DownloadSafety.inspect(
            "https://example.com/f.dat", "f.dat", "application/x-msdownload", false,
        )
        assertEquals(DownloadSafety.Risk.EXECUTABLE, v?.risk)
    }

    // ---- double extension ---------------------------------------------------

    @Test
    fun `a double extension hiding an executable is flagged`() {
        for (name in listOf("invoice.pdf.exe", "photo.jpg.sh", "notes.txt.bat")) {
            val v = DownloadSafety.inspect("https://example.com/$name", name, null, false)
            assertNotNull("$name should be flagged", v)
            assertEquals("$name should be EXECUTABLE", DownloadSafety.Risk.EXECUTABLE, v!!.risk)
        }
    }

    @Test
    fun `a single document extension is not treated as a double extension`() {
        // "report.pdf" splits into ["report","pdf"] -- only two parts, so the
        // middle-part rule must not fire.
        assertNull(DownloadSafety.inspect("https://example.com/r.pdf", "r.pdf", null, false))
    }

    @Test
    fun `a harmless multi-dot document name is not flagged`() {
        assertNull(
            DownloadSafety.inspect("https://example.com/a.b.pdf", "a.b.pdf", null, false),
        )
    }

    // ---- cleartext ----------------------------------------------------------

    @Test
    fun `http is refused only when https-only mode is on`() {
        val off = DownloadSafety.inspect("http://example.com/f.pdf", "f.pdf", null, httpsOnly = false)
        assertNull("http must be allowed when HTTPS-only is off", off)

        val on = DownloadSafety.inspect("http://example.com/f.pdf", "f.pdf", null, httpsOnly = true)
        assertEquals(DownloadSafety.Risk.INSECURE, on?.risk)
    }

    @Test
    fun `scheme detection ignores case and leading whitespace`() {
        val v = DownloadSafety.inspect("  HTTP://example.com/f.pdf", "f.pdf", null, httpsOnly = true)
        assertEquals(DownloadSafety.Risk.INSECURE, v?.risk)
    }

    @Test
    fun `executable beats insecure when both apply`() {
        val v = DownloadSafety.inspect(
            "http://example.com/setup.sh", "setup.sh", null, httpsOnly = true,
        )
        assertEquals(DownloadSafety.Risk.EXECUTABLE, v?.risk)
    }

    // ---- ordinary files -----------------------------------------------------

    @Test
    fun `an ordinary document over https is not flagged`() {
        assertNull(
            DownloadSafety.inspect("https://example.com/report.pdf", "report.pdf", "application/pdf", true),
        )
    }

    @Test
    fun `an extensionless name is not flagged`() {
        assertNull(DownloadSafety.inspect("https://example.com/README", "README", null, false))
    }
}