package com.example.netyantra.portal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SophosPortalClientTest {

    @Test
    fun extractStatus_withCdata() {
        val xml = """
            <response>
                <status><![CDATA[LIVE]]></status>
                <message><![CDATA[You are signed in as {username}]]></message>
            </response>
        """.trimIndent()

        val status = SophosPortalClient.extractStatus(xml)
        assertEquals("live", status)
        assertEquals("You are signed in as {username}", SophosPortalClient.extractMessage(xml))
    }

    @Test
    fun extractStatus_withoutCdata() {
        val xml = """
            <response>
                <status>LIVE</status>
                <message>You are signed in as testuser</message>
            </response>
        """.trimIndent()

        val status = SophosPortalClient.extractStatus(xml)
        assertEquals("live", status)
    }

    @Test
    fun extractStatus_login() {
        val xml = """
            <response>
                <status><![CDATA[LOGIN]]></status>
                <message><![CDATA[You have successfully logged off]]></message>
            </response>
        """.trimIndent()

        val status = SophosPortalClient.extractStatus(xml)
        assertEquals("login", status)
        assertEquals("You have successfully logged off", SophosPortalClient.extractMessage(xml))
    }

    @Test
    fun extractStatus_failure() {
        val xml = """
            <response>
                <status><![CDATA[FAIL]]></status>
                <message><![CDATA[The system could not log you on. Make sure your password is correct]]></message>
            </response>
        """.trimIndent()

        val status = SophosPortalClient.extractStatus(xml)
        assertEquals("fail", status)
        assertFalse(status == "live")
    }

    @Test
    fun loginSuccess_basedStrictlyOnStatusLive() {
        // Portal responds with template {username}
        val xmlWithTemplate = """
            <response>
                <status><![CDATA[LIVE]]></status>
                <message><![CDATA[You are signed in as {username}]]></message>
            </response>
        """.trimIndent()

        val status = SophosPortalClient.extractStatus(xmlWithTemplate)
        val isSuccess = status == "live"
        assertTrue("Status live must be evaluated as success regardless of message text", isSuccess)
    }
}
