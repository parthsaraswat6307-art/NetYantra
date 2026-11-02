package com.example.netyantra.portal

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PortalTest {

    @Test
    fun testPortalProfileSerialization() {
        val profile = PortalProfile(
            id = "test-uuid-1234",
            ssid = "Campus-WiFi",
            loginUrl = "http://192.168.1.1/login",
            usernameField = "auth_user",
            passwordField = "auth_pass",
            extraFields = mapOf("magic" to "abc123xyz", "terms" to "accepted"),
            username = "student_user",
            password = "secure_password"
        )

        // 1. Serialization without password
        val jsonNoPass = profile.toJson(includePassword = false).toString()
        assertFalse("Serialized JSON must not contain plaintext password when excluded", jsonNoPass.contains("secure_password"))

        // 2. Serialization with password
        val jsonWithPass = profile.toJson(includePassword = true).toString()
        val restored = PortalProfile.fromJson(jsonWithPass)

        assertNotNull(restored)
        assertEquals("test-uuid-1234", restored?.id)
        assertEquals("Campus-WiFi", restored?.ssid)
        assertEquals("http://192.168.1.1/login", restored?.loginUrl)
        assertEquals("auth_user", restored?.usernameField)
        assertEquals("auth_pass", restored?.passwordField)
        assertEquals("student_user", restored?.username)
        assertEquals("secure_password", restored?.password)
        assertEquals(2, restored?.extraFields?.size)
        assertEquals("abc123xyz", restored?.extraFields?.get("magic"))
        assertEquals("accepted", restored?.extraFields?.get("terms"))
    }

    @Test
    fun testDetectorReturnsNoneOn204() = runBlocking {
        // Interceptor simulates genuine HTTP 204 No Content
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("No Content")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            })
            .build()

        val detector = DefaultCaptivePortalDetector()
        val result = detector.checkForCaptivePortal(client)

        assertTrue("HTTP 204 must return PortalState.None", result is PortalState.None)
    }

    @Test
    fun testDetectorReturnsDetectedOnRedirect() = runBlocking {
        // Interceptor simulates HTTP 302 redirect with Location header
        val targetRedirectUrl = "http://captive.portal.local/login.html"
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", targetRedirectUrl)
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            })
            .build()

        val detector = DefaultCaptivePortalDetector()
        val result = detector.checkForCaptivePortal(client)

        assertTrue("HTTP 302 must return PortalState.Detected", result is PortalState.Detected)
        val detected = result as PortalState.Detected
        assertEquals(targetRedirectUrl, detected.loginUrl)
    }

    @Test
    fun testDetectorReturnsDetectedOnNon204Html() = runBlocking {
        // Interceptor simulates HTTP 200 containing captive portal HTML
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html><body>Please log in</body></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            })
            .build()

        val detector = DefaultCaptivePortalDetector()
        val result = detector.checkForCaptivePortal(client)

        assertTrue("HTTP 200 non-204 must detect captive portal", result is PortalState.Detected)
    }

    @Test
    fun testDetectorHandlesConnectionExceptionGracefully() = runBlocking {
        // Interceptor simulates connection failure (firewall dropping packets)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { _ ->
                throw IOException("Failed to connect to connectivitycheck.gstatic.com/")
            })
            .build()

        val detector = DefaultCaptivePortalDetector()
        val result = detector.checkForCaptivePortal(client)

        assertTrue("Connection drop should return PortalState.Error", result is PortalState.Error)
    }

    @Test
    fun testDetectorAppleProbeSuccessReturnsNone() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("hotspot-detect")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    throw IOException("Connection refused")
                }
            })
            .build()

        val detector = DefaultCaptivePortalDetector()
        val result = detector.checkForCaptivePortal(client)

        assertTrue("Apple hotspot Success probe should return PortalState.None", result is PortalState.None)
    }

    @Test
    fun testDetectorExtractsSophosHttpClientUrlFromScript() = runBlocking {
        val sophosUrl = "http://172.16.16.16:8090/httpclient.html"
        val htmlWithScript = """
            <html>
            <head>
            <script language="javascript">
            window.location.href="$sophosUrl";
            </script>
            </head>
            <body>Please log in</body>
            </html>
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(htmlWithScript.toResponseBody("text/html".toMediaType()))
                    .build()
            })
            .build()

        val detector = DefaultCaptivePortalDetector()
        val result = detector.checkForCaptivePortal(client)

        assertTrue(result is PortalState.Detected)
        val detected = result as PortalState.Detected
        assertEquals(sophosUrl, detected.loginUrl)
    }

    @Test
    fun testDetectorDoesNotReturnGenerate204AsLoginUrl() {
        val detector = DefaultCaptivePortalDetector()
        val extracted = detector.extractPortalLoginUrl(
            locationHeader = null,
            body = "<html>Generic blocking page without explicit URL</html>",
            requestUrl = "http://connectivitycheck.gstatic.com/generate_204"
        )
        assertTrue("generate_204 check URL must not be returned as the captive portal login URL", extracted.isEmpty())
    }
}
