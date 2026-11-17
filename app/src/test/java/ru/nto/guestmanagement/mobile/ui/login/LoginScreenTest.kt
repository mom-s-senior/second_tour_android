package ru.nto.guestmanagement.mobile.ui.login

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.nto.guestmanagement.mobile.Constants
import ru.nto.guestmanagement.mobile.data.SystemTimeProvider
import ru.nto.guestmanagement.mobile.di.AppContainer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-port")
class LoginScreenTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var originalHost: String
    private lateinit var storageScope: CoroutineScope
    private lateinit var container: AppContainer
    private val requests = CopyOnWriteArrayList<String>()
    private val status = AtomicInteger(200)
    private val loggedIn = AtomicInteger()
    @Volatile private var releaseResponse = CountDownLatch(0)

    @Before fun setUp() {
        originalHost = Constants.HOST
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                requests += exchange.requestURI.path
                if (!releaseResponse.await(8, TimeUnit.SECONDS)) return@createContext
                val response = status.get()
                val body = if (response == 200) """{"name":"Test Employee"}""" else """{"error":"Test error"}"""
                val bytes = body.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(response, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        Constants.HOST = "http://127.0.0.1:${server.address.port}"
        storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        container = AppContainer(
            CIO.create(),
            PreferenceDataStoreFactory.create(scope = storageScope) {
                temp.root.resolve("session.preferences_pb")
            },
            SystemTimeProvider,
        )
    }

    @After fun tearDown() {
        releaseResponse.countDown()
        container.httpClient.close()
        storageScope.cancel()
        server.stop(0)
        Constants.HOST = originalHost
    }

    @Test fun formatBlocksInvalidInputAndKeepsItUnchanged() {
        show()
        node("auth_submit").assertIsDisplayed().assertIsNotEnabled()
        for (invalid in listOf("aB7", "aB789", "a B7", "Аб12")) {
            node("auth_code").performTextReplacement(invalid)
            assertCode(invalid)
            node("auth_submit").assertIsNotEnabled()
        }
        for (valid in listOf("aB7Z", "1234", "AbCd")) {
            node("auth_code").performTextReplacement(valid)
            assertCode(valid)
            waitEnabled()
        }
        ui.onNodeWithText("Код сотрудника").assertIsDisplayed()
        node("auth_loading").assertDoesNotExist()
        node("auth_error").assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }

    @Test fun successfulRequestKeepsCaseAndPersistsCode() {
        show()
        submit("mN5P")
        ui.waitUntil(10_000) { loggedIn.get() == 1 }
        assertEquals(listOf("/api/mN5P/auth"), requests)
        assertEquals("mN5P", runBlocking { container.session.current() })
        node("auth_loading").assertDoesNotExist()
        node("auth_error").assertDoesNotExist()
    }

    @Test fun unauthorizedClearsOnEditAndSameCodeCanBeRetried() {
        status.set(401)
        show()
        submit("rS4T")
        waitError("Неверный код сотрудника")
        assertEquals(0, loggedIn.get())
        assertNull(runBlocking { container.session.current() })
        node("auth_submit").assertIsEnabled()
        node("auth_code").performTextReplacement("rS4U")
        node("auth_error").assertDoesNotExist()
        submit("rS4T")
        waitError("Неверный код сотрудника")
        status.set(200)
        node("auth_submit").performClick()
        ui.waitUntil(10_000) { loggedIn.get() == 1 }
        assertEquals(List(3) { "/api/rS4T/auth" }, requests)
        node("auth_error").assertDoesNotExist()
    }

    @Test fun http500ShowsErrorAndReleasesButton() {
        status.set(500)
        show()
        submit("kL9M")
        waitError("Не удалось войти")
        node("auth_submit").assertIsDisplayed().assertIsEnabled()
        node("auth_loading").assertDoesNotExist()
        assertEquals(listOf("/api/kL9M/auth"), requests)
        assertEquals(0, loggedIn.get())
    }

    @Test fun networkFailureShowsErrorAndKeepsInput() {
        server.stop(0)
        show()
        submit("xY8Z")
        waitError("Нет связи")
        assertCode("xY8Z")
        node("auth_submit").assertIsEnabled()
        node("auth_loading").assertDoesNotExist()
        assertNull(runBlocking { container.session.current() })
    }

    @Test fun rapidClicksBeforeRecompositionProduceOnlyOneRequest() {
        releaseResponse = CountDownLatch(1)
        status.set(401)
        show()
        node("auth_code").performTextReplacement("uV9W")
        waitEnabled()
        val click = checkNotNull(node("auth_submit").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        ui.runOnIdle { repeat(5) { click() } }
        ui.waitUntil(10_000) { requests.isNotEmpty() }
        node("auth_loading").assertIsDisplayed()
        node("auth_submit").assertIsDisplayed().assertIsNotEnabled()
        releaseResponse.countDown()
        waitError("Неверный код сотрудника")
        node("auth_loading").assertDoesNotExist()
        node("auth_submit").assertIsEnabled()
        assertEquals(listOf("/api/uV9W/auth"), requests)
    }

    @Test fun freshScreenRestoresPersistedCodeWithoutAuth() {
        runBlocking {
            container.session.save("jK1L")
            storageScope.coroutineContext[Job]!!.cancelAndJoin()
        }
        storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        container.httpClient.close()
        container = AppContainer(
            CIO.create(),
            PreferenceDataStoreFactory.create(scope = storageScope) {
                temp.root.resolve("session.preferences_pb")
            },
            SystemTimeProvider,
        )
        show()
        ui.waitUntil(10_000) { loggedIn.get() == 1 }
        assertTrue(requests.isEmpty())
    }

    @Test fun expiredSessionDoesNotRestoreEvenWithStoredCode() {
        runBlocking { container.session.save("qR2S") }
        show(expired = true)
        node("auth_session_expired").assertIsDisplayed().assertTextEquals("Сессия завершена")
        node("auth_submit").assertIsNotEnabled()
        node("auth_code").performTextReplacement("qR3S")
        waitEnabled() // Экран успел завершить проверку сессии.
        assertEquals(0, loggedIn.get())
        assertTrue(requests.isEmpty())
    }

    @Test fun afterClearingSessionNewLoginStartsWithEmptyField() {
        runBlocking { container.session.save("dE6F") }
        val visible = mutableStateOf(true)
        ui.setContent {
            if (visible.value) LoginScreen(container, false) { loggedIn.incrementAndGet() }
        }
        ui.waitUntil(10_000) { loggedIn.get() == 1 }
        ui.runOnIdle { visible.value = false }
        ui.waitForIdle()
        runBlocking { container.session.clear() }
        ui.runOnIdle { visible.value = true }
        assertCode("")
        node("auth_submit").assertIsNotEnabled()
        node("auth_session_expired").assertDoesNotExist()
        assertEquals(1, loggedIn.get())
        assertTrue(requests.isEmpty())
    }

    private fun show(expired: Boolean = false) {
        ui.setContent { LoginScreen(container, expired) { loggedIn.incrementAndGet() } }
    }

    private fun node(tag: String) = ui.onNodeWithTag(tag)

    private fun assertCode(code: String) {
        node("auth_code").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(code)))
    }

    private fun submit(code: String) {
        node("auth_code").performTextReplacement(code)
        waitEnabled()
        node("auth_submit").performClick()
    }

    private fun waitEnabled() {
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("auth_submit") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitError(text: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("auth_error").fetchSemanticsNodes().isNotEmpty() }
        node("auth_error").assertIsDisplayed().assertTextEquals(text)
    }
}
