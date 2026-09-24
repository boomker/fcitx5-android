/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.predict

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmEndpointFailoverTest {
    @Test
    fun selectedEndpointsAreTriedInOrderWithTheirOwnCredentialsAndModels() = runBlocking {
        val attempted = mutableListOf<LlmPrefs.Config>()
        val result = predictWithEndpointFailover(request(), null) { request, _ ->
            attempted += request.config
            if (attempted.size == 1) throw LlmPredictionFailure.fromNetwork(SocketTimeoutException())
            success
        }
        assertSame(success, result)
        assertEquals(listOf(first.baseUrl, second.baseUrl), attempted.map { it.baseUrl })
        assertEquals(listOf(first.apiKey, second.apiKey), attempted.map { it.apiKey })
        assertEquals(listOf(first.model, second.model), attempted.map { it.model })
        assertTrue(attempted.all { it.remoteEndpoints.isEmpty() })
    }

    @Test
    fun firstSuccessDoesNotContactBackups() = runBlocking {
        var calls = 0
        assertSame(success, predictWithEndpointFailover(request(), null) { _, _ ->
            calls++
            success
        })
        assertEquals(1, calls)
    }

    @Test
    fun networkAndServiceFailuresCanUseAnotherEndpointImmediately() = runBlocking {
        val failures = listOf(
            LlmPredictionFailure.fromHttp(503, ""),
            LlmPredictionFailure.fromNetwork(SocketTimeoutException()),
        )
        for (failure in failures) {
            var calls = 0
            val result = predictWithEndpointFailover(request(), null) { _, _ ->
                if (++calls == 1) throw failure
                success
            }
            assertSame(success, result)
            assertEquals(2, calls)
        }
    }

    @Test
    fun authenticationAndQuotaFailuresDoNotFailOver() = runBlocking {
        val failures = listOf(
            LlmPredictionFailure.fromHttp(401, ""),
            LlmPredictionFailure.fromHttp(402, ""),
        )
        for (failure in failures) {
            var calls = 0
            val thrown = runCatching {
                predictWithEndpointFailover(request(), null) { _, _ ->
                    calls++
                    throw failure
                }
            }.exceptionOrNull()
            assertSame(failure, thrown)
            assertEquals(1, calls)
        }
    }

    @Test
    fun credentialsQuotaAndRateLimitAreShownFirstThenNextRequestUsesBackup() = runBlocking<Unit> {
        for (status in listOf(401, 403, 402, 429)) {
            val state = LlmEndpointFailoverState()
            val failure = LlmPredictionFailure.fromHttp(status, "")
            val attempted = mutableListOf<String>()
            assertSame(failure, runCatching {
                predictWithEndpointFailover(request(), null, state, { req, _ ->
                    attempted += req.config.baseUrl
                    throw failure
                })
            }.exceptionOrNull())
            assertEquals(listOf(first.baseUrl), attempted)
            assertTrue(failure.willSwitchEndpointOnNextRequest)

            predictWithEndpointFailover(request(), null, state, { req, _ ->
                attempted += req.config.baseUrl
                success
            })
            assertEquals(listOf(first.baseUrl, second.baseUrl), attempted)
            // A successful backup remains the first choice for later requests.
            predictWithEndpointFailover(request(), null, state, { req, _ ->
                attempted += req.config.baseUrl
                success
            })
            assertEquals(second.baseUrl, attempted.last())
        }
    }

    @Test
    fun subsequentDeferredFailuresMoveToNextEndpointWithoutSkipping() = runBlocking<Unit> {
        val state = LlmEndpointFailoverState()
        val attempted = mutableListOf<String>()
        repeat(2) {
            val thrown = runCatching {
                predictWithEndpointFailover(request(), null, state, { req, _ ->
                    attempted += req.config.baseUrl
                    throw LlmPredictionFailure.fromHttp(429, "")
                })
            }.exceptionOrNull()
            assertTrue(thrown is LlmPredictionFailure)
        }
        predictWithEndpointFailover(request(), null, state, { req, _ ->
            attempted += req.config.baseUrl
            success
        })
        assertEquals(listOf(first.baseUrl, second.baseUrl, third.baseUrl), attempted)
    }

    @Test
    fun changingEndpointSettingsResetsDeferredPreference() = runBlocking<Unit> {
        val state = LlmEndpointFailoverState()
        runCatching {
            predictWithEndpointFailover(request(), null, state, { _, _ ->
                throw LlmPredictionFailure.fromHttp(401, "")
            })
        }
        val revised = config().copy(remoteEndpoints = listOf(first.copy(apiKey = "new-key"), second))
        predictWithEndpointFailover(request().copy(config = revised), null, state, { req, _ ->
            assertEquals(first.baseUrl, req.config.baseUrl)
            assertEquals("new-key", req.config.apiKey)
            success
        })
    }

    @Test
    fun allFailedEndpointsReturnLastClassifiedFailureWithoutLooping() = runBlocking {
        var calls = 0
        val failure = LlmPredictionFailure.invalidResponse()
        val thrown = runCatching {
            predictWithEndpointFailover(request(), null) { _, _ ->
                calls++
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals(3, calls)
    }

    @Test
    fun legacySingleEndpointConfigIsUnchanged() = runBlocking {
        val legacy = config().copy(remoteEndpoints = emptyList())
        var calls = 0
        val failure = LlmPredictionFailure.invalidResponse()
        assertSame(failure, runCatching {
            predictWithEndpointFailover(request().copy(config = legacy), null) { req, _ ->
                assertSame(legacy, req.config)
                calls++
                throw failure
            }
        }.exceptionOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun oneSelectedEndpointNeverFallsBackToLegacyPrimary() = runBlocking {
        val onlySecond = config().copy(remoteEndpoints = listOf(second))
        var calls = 0
        predictWithEndpointFailover(request().copy(config = onlySecond), null) { req, _ ->
            assertEquals(second.baseUrl, req.config.baseUrl)
            calls++
            success
        }
        assertEquals(1, calls)
    }

    @Test
    fun aSingleEnabledEndpointDoesNotClaimADeferredSwitch() = runBlocking<Unit> {
        val state = LlmEndpointFailoverState()
        val failure = LlmPredictionFailure.fromHttp(401, "")
        val onlyFirst = config().copy(remoteEndpoints = listOf(first))
        assertSame(failure, runCatching {
            predictWithEndpointFailover(request().copy(config = onlyFirst), null, state) { _, _ ->
                throw failure
            }
        }.exceptionOrNull())
        assertFalse(failure.willSwitchEndpointOnNextRequest)
    }

    @Test
    fun partialSnapshotIsClearedBeforeBackupStarts() = runBlocking {
        val partials = mutableListOf<String>()
        var calls = 0
        predictWithEndpointFailover(request(), partials::add) { _, partial ->
            if (++calls == 1) {
                partial?.invoke("failed partial")
                throw LlmPredictionFailure.invalidResponse()
            }
            assertEquals(listOf("failed partial", ""), partials)
            partial?.invoke("backup answer")
            success
        }
        assertEquals(listOf("failed partial", "", "backup answer"), partials)
    }

    @Test
    fun cancellationIsNeverTreatedAsEndpointFailure() = runBlocking {
        var calls = 0
        val cancelled = CancellationException("test")
        assertSame(cancelled, runCatching {
            predictWithEndpointFailover(request(), null) { _, _ ->
                calls++
                throw cancelled
            }
        }.exceptionOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun cancelledJobDoesNotStartNextEndpointAfterBlockingCallFails() {
        var calls = 0
        val thrown = runCatching {
            runBlocking {
                val job = currentCoroutineContext()[Job]!!
                predictWithEndpointFailover(request(), null) { _, _ ->
                    calls++
                    job.cancel()
                    throw LlmPredictionFailure.fromNetwork(SocketTimeoutException())
                }
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(1, calls)
    }

    @Test
    fun unexpectedProgrammingErrorsDoNotTriggerNetworkRequests() = runBlocking {
        var calls = 0
        val failure = IllegalStateException("bug")
        assertSame(failure, runCatching {
            predictWithEndpointFailover(request(), null) { _, _ ->
                calls++
                throw failure
            }
        }.exceptionOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun failoverPreservesRequestModesAndGenerationOptions() = runBlocking {
        for (backend in LlmPrefs.Backend.entries) {
            for (mode in LlmTaskMode.entries) {
                val original = request().copy(
                    config = config().copy(backend = backend),
                    outputMode = LlmOutputMode.LongForm,
                    taskMode = mode,
                    enableThinking = true,
                    translationCorrectionAttempt = true,
                    seed = 123,
                )
                var calls = 0
                predictWithEndpointFailover(original, null) { req, _ ->
                    assertEquals(original.copy(config = req.config), req)
                    assertEquals(backend, req.config.backend)
                    if (++calls == 1) throw LlmPredictionFailure.invalidResponse()
                    success
                }
                assertEquals(2, calls)
            }
        }
    }

    @Test
    fun laterRequestsStartInConfiguredPriorityOrderAgain() = runBlocking {
        val calls = mutableListOf<String>()
        repeat(2) {
            predictWithEndpointFailover(request(), null) { req, _ ->
                calls += req.config.baseUrl
                if (req.config.baseUrl == first.baseUrl) throw LlmPredictionFailure.invalidResponse()
                success
            }
        }
        assertEquals(listOf(first.baseUrl, second.baseUrl, first.baseUrl, second.baseUrl), calls)
        assertFalse(calls.contains(third.baseUrl))
    }

    private fun request() = LlmClient.PredictionRequest(config(), "hello")

    private fun config() = LlmPrefs.Config(
        enabled = true,
        backend = LlmPrefs.Backend.ChatCompletions,
        baseUrl = first.baseUrl,
        model = first.model,
        apiKey = first.apiKey,
        debounceMs = 0,
        sampleCount = 1,
        maxContextChars = 64,
        preferLastCommit = true,
        remoteEndpoints = listOf(first, second, third),
    )

    private val first = LlmPrefs.RemoteEndpoint("https://first.example/v1", "first-model", "first-key")
    private val second = LlmPrefs.RemoteEndpoint("https://second.example/v1", "second-model", "second-key")
    private val third = LlmPrefs.RemoteEndpoint("https://third.example/v1", "third-model", "third-key")
    private val success = LlmClient.PredictionResponse(listOf("answer"), "answer")
}
