package net.corda.rest.client

import net.corda.rest.RestResource
import net.corda.rest.client.config.RestClientConfig
import net.corda.rest.client.connect.RestClientProxyHandler
import net.corda.rest.test.TestHealthCheckAPI
import net.corda.test.util.eventually
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RestClientTest {
    private inline fun <reified I : RestResource> mockProxyGenerator(
        restResourceClass: Class<I>, proxyHandler: RestClientProxyHandler<I>): I {
        assertNotNull(proxyHandler)
        assertNotNull(restResourceClass)
        return mock<I>().also {
            doReturn(2).whenever(it).protocolVersion
        }
    }

    private inline fun <reified I : RestResource> mockErrorProxyGenerator(
        restResourceClass: Class<I>, proxyHandler: RestClientProxyHandler<I>): I {
        assertNotNull(proxyHandler)
        assertNotNull(restResourceClass)
        return mock<I>().also {
            doThrow(RuntimeException()).whenever(it).protocolVersion
        }
    }

    @Test
    fun `should start health checking server after start is called`() {
        val client = RestClient(
            baseAddress = "",
            restResourceClass = TestHealthCheckAPI::class.java,
            clientConfig = RestClientConfig()
                .minimumServerProtocolVersion(1),
            healthCheckInterval = 100,
            proxyGenerator = this::mockProxyGenerator
        )

        client.use {
            client.start()
            eventually {
                verify(it.ops, atLeast(4)).protocolVersion
            }
        }

        //counting invocations after closing the client, because otherwise extra invocations could be done between counting and closing
        val healthCheckInvocations = mockingDetails(client.ops).invocations.count { it.method.name.contains("getProtocolVersion") }

        // checks that daemon was cancelled
        eventually {
            verify(client.ops, atLeast(healthCheckInvocations)).protocolVersion
        }
    }

    @Test
    fun `instantiating a client with higher minimum server protocol version throws exception`() {
        val client = RestClient(
            baseAddress = "",
            restResourceClass = TestHealthCheckAPI::class.java,
            clientConfig = RestClientConfig()
                .minimumServerProtocolVersion(4),
            healthCheckInterval = 100,
            proxyGenerator = this::mockProxyGenerator
        )

        val exception = assertThrows<IllegalArgumentException> {
            client.start()
        }
        assertEquals("Requested minimum protocol version (4) is higher than the server's supported protocol " +
                "version (2)", exception.message)
    }

    @Test
    fun `instantiating a client with equal minimum server protocol version doesn't throw exception`() {
        val client = RestClient(
            baseAddress = "",
            restResourceClass = TestHealthCheckAPI::class.java,
            clientConfig = RestClientConfig()
                .minimumServerProtocolVersion(2),
            healthCheckInterval = 100,
            proxyGenerator = this::mockProxyGenerator
        )

        client.use {
            val connection = client.start()
            assertEquals(2, connection.serverProtocolVersion)
        }
    }

    @Test
    fun `connecting listener to the client and then starting it makes messages be transmitted successfully`() {
        val client = RestClient(
            baseAddress = "",
            restResourceClass = TestHealthCheckAPI::class.java,
            clientConfig = RestClientConfig()
                .minimumServerProtocolVersion(1),
            healthCheckInterval = 100,
            proxyGenerator = this::mockProxyGenerator
        )

        val listener = mock<RestConnectionListener<TestHealthCheckAPI>>()

        client.use {
            client.addConnectionListener(listener)
            client.start()

            eventually {
                // may be more depending on the machine's performance and thread invocation sequence
                verify(it.ops, atLeast(4)).protocolVersion
            }
            verify(listener, times(1)).onConnect(any())
        }

        verify(listener, times(1)).onDisconnect(any())
    }

    @Test
    fun `connecting listener to the client after starting it makes messages to be transmitted successfully`() {
        val client = RestClient(
            baseAddress = "",
            restResourceClass = TestHealthCheckAPI::class.java,
            clientConfig = RestClientConfig()
                .minimumServerProtocolVersion(1),
            healthCheckInterval = 100,
            proxyGenerator = this::mockProxyGenerator
        )

        val listener = mock<RestConnectionListener<TestHealthCheckAPI>>()

        client.use {
            client.addConnectionListener(listener)
            client.start()
            verify(listener, times(1)).onConnect(any())
        }

        verify(listener, times(1)).onDisconnect(any())
    }

    @Test
    fun `connecting listener to the client and then starting it but failing to connect makes error messages be transmitted successfully`() {
        val client = RestClient(
            baseAddress = "",
            restResourceClass = TestHealthCheckAPI::class.java,
            clientConfig = RestClientConfig()
                .minimumServerProtocolVersion(1),
            healthCheckInterval = 100,
            proxyGenerator = this::mockErrorProxyGenerator
        )

        val listener = mock<RestConnectionListener<TestHealthCheckAPI>>()

        client.use {
            client.addConnectionListener(listener)
            assertThatThrownBy { client.start() }.isInstanceOf(RuntimeException::class.java)
            eventually {
                verify(listener, times(0)).onConnect(any())
            }

        }

        verify(listener, times(0)).onDisconnect(any())
        verify(listener, times(1)).onPermanentFailure(any())
    }
}