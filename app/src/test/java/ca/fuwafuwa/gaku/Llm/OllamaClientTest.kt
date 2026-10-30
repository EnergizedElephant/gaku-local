package ca.fuwafuwa.gaku.Llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URL

class OllamaClientTest
{
    private fun isLocal(address: String) = OllamaClient.isLocalAddress(InetAddress.getByName(address))

    @Test
    fun localNetworkAddressesAreLocal()
    {
        for (address in listOf("127.0.0.1", "10.0.2.2", "172.16.0.5", "172.31.255.1", "192.168.1.10",
                "169.254.10.10", "100.64.0.1", "100.127.255.254", "::1", "fe80::1", "fd12:3456::1", "fc00::1"))
        {
            assertTrue(address, isLocal(address))
        }
    }

    @Test
    fun publicAddressesAreNotLocal()
    {
        for (address in listOf("8.8.8.8", "172.32.0.1", "172.15.255.255", "100.63.255.255", "100.128.0.1",
                "192.169.0.1", "2001:4860:4860::8888", "::ffff:8.8.8.8"))
        {
            assertFalse(address, isLocal(address))
        }
    }

    @Test
    fun serverAddressesAreCompleted()
    {
        assertEquals("http://192.168.1.10:11434", OllamaClient.parseServer("192.168.1.10").toString())
        assertEquals("http://192.168.1.10:8080", OllamaClient.parseServer(" 192.168.1.10:8080/ ").toString())
        assertEquals("http://ollama.example.com:11434", OllamaClient.parseServer("http://ollama.example.com").toString())
        assertEquals("http://[fd00::1]:11434", OllamaClient.parseServer("http://[fd00::1]").toString())
        assertNull(OllamaClient.parseServer(""))
        assertNull(OllamaClient.parseServer("ftp://192.168.1.10"))
    }

    @Test
    fun httpsWithoutAPortUses443()
    {
        assertEquals("https://ollama.com:443", OllamaClient.parseServer("https://ollama.com").toString())
        assertEquals("https://ollama.lan:8443", OllamaClient.parseServer("https://ollama.lan:8443").toString())
    }

    @Test
    fun aPathIsKeptButNotATrailingApi()
    {
        assertEquals("https://example.com:443/ollama", OllamaClient.parseServer("https://example.com/ollama/").toString())
        assertEquals("https://example.com:443/ollama", OllamaClient.parseServer("https://example.com/ollama/api").toString())
        assertEquals("https://ollama.lan:443", OllamaClient.parseServer("https://ollama.lan:443/api").toString())
    }

    @Test
    fun onlyPlainHttpOutsideTheLocalNetworkIsUnencrypted()
    {
        fun unencrypted(url: String, vararg addresses: String) =
                OllamaClient.isUnencryptedOutside(URL(url), addresses.map { InetAddress.getByName(it) }.toTypedArray())

        assertTrue(unencrypted("http://ollama.example.com:11434", "8.8.8.8"))
        // Any public address among them counts
        assertTrue(unencrypted("http://ollama.example.com:11434", "192.168.1.10", "8.8.8.8"))
        assertFalse(unencrypted("https://ollama.example.com:443", "8.8.8.8"))
        assertFalse(unencrypted("http://192.168.1.10:11434", "192.168.1.10"))
        assertFalse(unencrypted("http://100.101.102.103:11434", "100.101.102.103"))
    }

    @Test
    fun streamedLinesAreRead()
    {
        val text = OllamaClient.parseChatLine("""{"model":"gemma3:12b","message":{"role":"assistant","content":" cats"},"done":false}""")!!
        assertEquals(" cats", text.content)
        assertFalse(text.thinking)
        assertFalse(text.done)

        val thinking = OllamaClient.parseChatLine("""{"message":{"role":"assistant","content":"","thinking":"Hmm"},"done":false}""")!!
        assertEquals("", thinking.content)
        assertTrue(thinking.thinking)

        val done = OllamaClient.parseChatLine("""{"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}""")!!
        assertTrue(done.done)

        val error = OllamaClient.parseChatLine("""{"error":"model 'nope:1b' not found"}""")!!
        assertEquals("model 'nope:1b' not found", error.error)

        assertNull(OllamaClient.parseChatLine(""))
    }
}
