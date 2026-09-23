package org.fxboomk.fcitx5.android.input.predict

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPredictionFailureTest {
    @Test
    fun classifiesBillingAndQuotaBeforeRateLimitStatus() {
        val paymentRequired = LlmPredictionFailure.fromHttp(402, "secret provider body")
        val insufficientQuota = LlmPredictionFailure.fromHttp(
            429,
            """{"error":{"message":"quota exhausted","type":"insufficient_quota","code":"insufficient_quota"}}""",
        )
        val balance = LlmPredictionFailure.fromHttp(
            400,
            """{"error":{"code":"balance_not_enough","message":"sensitive account details"}}""",
        )

        assertEquals(LlmPredictionFailure.Kind.BILLING_OR_QUOTA, paymentRequired.kind)
        assertEquals(LlmPredictionFailure.Kind.BILLING_OR_QUOTA, insufficientQuota.kind)
        assertEquals("insufficient_quota", insufficientQuota.providerCode)
        assertEquals(LlmPredictionFailure.Kind.BILLING_OR_QUOTA, balance.kind)
        assertFalse(paymentRequired.retryable)
    }

    @Test
    fun preservesProviderBalanceMessageAndPrioritizesItOverHttp429() {
        val failure = LlmPredictionFailure.fromHttp(
            429,
            """{"error":{"code":"1113","message":"余额不足，请充值后重试"}}""",
        )

        assertEquals(LlmPredictionFailure.Kind.BILLING_OR_QUOTA, failure.kind)
        assertEquals("余额不足，请充值后重试", failure.providerMessage)
    }

    @Test
    fun classifiesNumericTopLevelZhipuBalanceCode() {
        val failure = LlmPredictionFailure.fromHttp(
            429,
            """{"code":1113,"message":"余额不足或无资源包"}""",
        )

        assertEquals(LlmPredictionFailure.Kind.BILLING_OR_QUOTA, failure.kind)
        assertEquals("1113", failure.providerCode)
        assertEquals("余额不足或无资源包", failure.providerMessage)
    }

    @Test
    fun classifiesAuthRateLimitAndServiceFailures() {
        assertEquals(
            LlmPredictionFailure.Kind.AUTHENTICATION,
            LlmPredictionFailure.fromHttp(401, "{}").kind,
        )
        assertEquals(
            LlmPredictionFailure.Kind.AUTHENTICATION,
            LlmPredictionFailure.fromHttp(403, "{}").kind,
        )
        assertEquals(
            LlmPredictionFailure.Kind.RATE_LIMIT,
            LlmPredictionFailure.fromHttp(429, "{}").kind,
        )
        assertEquals(
            LlmPredictionFailure.Kind.SERVICE_UNAVAILABLE,
            LlmPredictionFailure.fromHttp(503, "{}").kind,
        )
        assertTrue(LlmPredictionFailure.fromHttp(429, "{}").retryable)
        assertTrue(LlmPredictionFailure.fromHttp(503, "{}").retryable)
    }

    @Test
    fun classifiesNetworkAndTimeoutWithoutExposingCauseText() {
        val secret = "https://example.invalid?api_key=secret"
        val failure = LlmPredictionFailure.fromNetwork(SocketTimeoutException(secret))

        assertEquals(LlmPredictionFailure.Kind.NETWORK, failure.kind)
        assertTrue(failure.retryable)
        assertFalse(failure.userMessage.contains("secret"))
        assertFalse(failure.message.orEmpty().contains("api_key"))
    }

    @Test
    fun detectsHttp200ProviderErrorEnvelope() {
        val failure = LlmPredictionFailure.classifyProviderEnvelope(
            """{"error":{"type":"invalid_request_error","message":"provider secret details"}}""",
        )

        assertEquals(LlmPredictionFailure.Kind.INVALID_RESPONSE, failure?.kind)
        assertFalse(failure?.userMessage.orEmpty().contains("provider secret details"))
    }

    @Test
    fun detectsAnthropicStyleStreamErrorEnvelope() {
        val failure = LlmPredictionFailure.classifyProviderEnvelope(
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )

        assertEquals(LlmPredictionFailure.Kind.SERVICE_UNAVAILABLE, failure?.kind)
        assertEquals("overloaded_error", failure?.providerCode)
        assertTrue(failure?.retryable == true)
    }

    @Test
    fun ignoresSuccessfulResponseEnvelope() {
        assertNull(
            LlmPredictionFailure.classifyProviderEnvelope(
                """{"choices":[{"message":{"content":"ok"}}]}""",
            )
        )
    }

    @Test
    fun invalidResponseIsSanitizedAndNotRetryable() {
        val failure = LlmPredictionFailure.invalidResponse()

        assertEquals(LlmPredictionFailure.Kind.INVALID_RESPONSE, failure.kind)
        assertFalse(failure.retryable)
        assertNull(failure.httpStatus)
    }
}
