package com.palash.librewolfandroid

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * A deterministic HTTP server for instrumentation tests.
 *
 * Core regression tests must not depend on the public internet: an external
 * site can be slow, blocked, changed or down, and every one of those produces
 * a test failure that says nothing about this app. So the pages come from
 * here, on loopback, with fixed titles, fixed bodies and fixed markup that a
 * test can assert against exactly.
 *
 * Written on a raw [ServerSocket] rather than pulling in an HTTP library: the
 * surface needed is a handful of GET routes returning fixed bytes, and a test
 * dependency is a cost the production build never sees.
 *
 * Routes:
 *   /page1, /page2, /page3   fixed HTML with a unique <title>
 *   /slow                    never responds, for load-in-progress states
 *   /status/<code>           responds with that HTTP status
 *   /redirect                302 to /page1
 *   /big                     a bounded but large body, for memory runs
 *   /count                   how many requests have been served
 */
class FixtureServer(
    private val bigBodyBytes: Int = 0,
) : AutoCloseable {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val hits = ConcurrentHashMap<String, Int>()
    private val sockets = mutableListOf<Socket>()

    /** The loopback origin, e.g. http://127.0.0.1:41234 */
    val base: String get() = "http://127.0.0.1:${server.localPort}"

    @Volatile
    private var running = true

    private val thread = Thread({ serve() }, "fixture-server").apply {
        isDaemon = true
        start()
    }

    fun url(path: String) = "$base$path"

    /** How many times a path has been requested. */
    fun hits(path: String): Int = hits[path] ?: 0

    private fun serve() {
        while (running) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            synchronized(sockets) { sockets.add(client) }
            Thread({ handle(client) }, "fixture-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Drain the headers; the fixtures never read a body.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
                hits.merge(path, 1, Int::plus)

                when {
                    path.startsWith("/status/") -> {
                        val code = path.removePrefix("/status/").toIntOrNull() ?: 200
                        respond(c, code, errorPage(code))
                    }
                    path == "/redirect" -> {
                        // One response, written once. An earlier version called
                        // sendHeaders() and then wrote a second complete
                        // status line, producing two concatenated HTTP
                        // responses; the engine could not parse it and the
                        // redirect test failed for a reason that had nothing
                        // to do with the browser.
                        write(
                            c,
                            "HTTP/1.1 302 Found\r\n" +
                                "Location: $base/page1\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: 0\r\n" +
                                "Cache-Control: no-store\r\n" +
                                "Connection: close\r\n\r\n",
                        )
                    }
                    path == "/slow" -> {
                        // Deliberately never answer. The client should be left
                        // in a loading state until something cancels it.
                        Thread.sleep(120_000)
                    }
                    path == "/big" && bigBodyBytes > 0 -> {
                        val body = buildString {
                            append("<!doctype html><title>Big</title><body>")
                            append("x".repeat(bigBodyBytes))
                            append("</body>")
                        }
                        respond(c, 200, body)
                    }
                    else -> respond(c, 200, page(path))
                }
            }
        } catch (_: Exception) {
            // A client that hangs up early is normal here.
        }
    }

    private fun page(path: String): String {
        val n = path.removePrefix("/page").ifBlank { "1" }.ifBlank { "1" }
        return """<!doctype html>
<html><head><title>Fixture Page $n</title></head>
<body><h1 id="fixture">Fixture Page $n</h1>
<p>Deterministic local fixture for $path.</p></body></html>"""
    }

    private fun errorPage(code: Int) =
        "<!doctype html><html><head><title>Error $code</title></head><body>$code</body></html>"

    private fun respond(client: Socket, code: Int, body: String) {
        sendHeaders(client, code, "OK", body.toByteArray().size)
        write(client, body)
    }

    private fun sendHeaders(c: Socket, code: Int, reason: String, length: Int) {
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: $length\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        write(c, head)
    }

    private fun write(c: Socket, text: String) {
        val out: OutputStream = c.getOutputStream()
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    override fun close() {
        running = false
        synchronized(sockets) {
            for (s in sockets) runCatching { s.close() }
            sockets.clear()
        }
        runCatching { server.close() }
        runCatching { thread.join(1_000) }
    }
}