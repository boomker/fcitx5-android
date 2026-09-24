package org.fxboomk.fcitx5.android.input.predict

import java.io.IOException

internal class LlmPredictionFailure private constructor(
    val kind: Kind,
    val retryable: Boolean,
    val httpStatus: Int? = null,
    val providerCode: String? = null,
    val providerMessage: String? = null,
    cause: Throwable? = null,
) : Exception(kind.userMessage, cause) {
    enum class Kind(val userMessage: String) {
        BILLING_OR_QUOTA("The model service has no available quota or balance."),
        AUTHENTICATION("The model service rejected the API credentials."),
        RATE_LIMIT("The model service is temporarily rate limited."),
        SERVICE_UNAVAILABLE("The model service is temporarily unavailable."),
        NETWORK("The model service could not be reached."),
        INVALID_RESPONSE("The model service returned an invalid or empty response."),
    }

    val userMessage: String
        get() = kind.userMessage

    /** UI hint for a classified failure that will change the endpoint on the next request. */
    internal var willSwitchEndpointOnNextRequest: Boolean = false

    companion object {
        private val billingMarkers = listOf(
            "insufficient_quota",
            "insufficient quota",
            "quota_exceeded",
            "quota exceeded",
            "billing",
            "balance",
            "payment_required",
            "payment required",
            "credit exhausted",
            "credits exhausted",
            "余额不足",
            "余额不够",
            "额度不足",
            "账户余额",
            "欠费",
            "无可用余额",
        )


        private val billingCodes = setOf(
            "1113", "1308", "1309", "1310", "1311", "1314",
            "1316", "1317", "1318", "1319", "1320", "1321",
        )

        private val rateLimitCodes = setOf("1302", "1313")
        private val serviceCodes = setOf("1305")

        private val authenticationMarkers = listOf(
            "authentication_error",
            "invalid_api_key",
            "invalid api key",
            "unauthorized",
            "forbidden",
        )

        private val rateLimitMarkers = listOf(
            "rate_limit",
            "rate limit",
            "too_many_requests",
            "too many requests",
            "请求过于频繁",
            "请求频率过高",
        )

        private val serviceMarkers = listOf(
            "overloaded",
            "server_error",
            "internal_error",
            "service_unavailable",
            "temporarily unavailable",
        )

        fun fromHttp(statusCode: Int, responseBody: String): LlmPredictionFailure {
            val providerError = providerErrorDetails(responseBody)
            val markerText = listOfNotNull(providerError?.code, providerError?.type, providerError?.message)
                .joinToString(" ")
                .lowercase()
            val kind = when {
                billingMarkers.any(markerText::contains) ||
                    providerError?.code in billingCodes ||
                    statusCode == 402 -> Kind.BILLING_OR_QUOTA
                authenticationMarkers.any(markerText::contains) || statusCode == 401 || statusCode == 403 ->
                    Kind.AUTHENTICATION
                rateLimitMarkers.any(markerText::contains) ||
                    providerError?.code in rateLimitCodes ||
                    statusCode == 429 -> Kind.RATE_LIMIT
                serviceMarkers.any(markerText::contains) ||
                    providerError?.code in serviceCodes ||
                    statusCode in 500..599 -> Kind.SERVICE_UNAVAILABLE
                else -> Kind.INVALID_RESPONSE
            }
            return create(
                kind = kind,
                httpStatus = statusCode,
                providerCode = providerError?.code ?: providerError?.type,
                providerMessage = providerError?.message,
            )
        }

        fun fromNetwork(cause: IOException): LlmPredictionFailure = create(
            kind = Kind.NETWORK,
            cause = cause,
        )

        fun invalidResponse(): LlmPredictionFailure = create(Kind.INVALID_RESPONSE)

        fun classifyProviderEnvelope(responseBody: String): LlmPredictionFailure? {
            if (providerErrorDetails(responseBody) == null) return null
            return fromHttp(statusCode = 200, responseBody = responseBody)
        }

        private fun create(
            kind: Kind,
            httpStatus: Int? = null,
            providerCode: String? = null,
            providerMessage: String? = null,
            cause: Throwable? = null,
        ) = LlmPredictionFailure(
            kind = kind,
            retryable = kind in setOf(Kind.RATE_LIMIT, Kind.SERVICE_UNAVAILABLE, Kind.NETWORK),
            httpStatus = httpStatus,
            providerCode = providerCode
                ?.takeIf { PROVIDER_CODE_REGEX.matches(it) }
                ?.take(80),
            providerMessage = providerMessage
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.take(160),
            cause = cause,
        )

        private fun providerErrorDetails(responseBody: String): ProviderErrorDetails? {
            val errorMatch = ERROR_FIELD_REGEX.find(responseBody)
            val hasErrorEnvelope = errorMatch != null ||
                TYPE_ERROR_REGEX.containsMatchIn(responseBody) ||
                STANDALONE_CODE_MESSAGE_REGEX.containsMatchIn(responseBody)
            if (!hasErrorEnvelope) return null

            val errorScope = errorMatch?.range?.first?.let(responseBody::substring) ?: responseBody
            return ProviderErrorDetails(
                code = extractCodeField(errorScope) ?: extractCodeField(responseBody),
                type = extractStringField(errorScope, "type") ?: extractStringField(responseBody, "type"),
                message = extractStringField(errorScope, "message")
                    ?: errorMatch?.groups?.get(1)?.value
                    ?: extractStringField(responseBody, "message"),
            )
        }

        private fun extractCodeField(raw: String): String? {
            val match = Regex(
                """"code"\s*:\s*(?:"((?:\\.|[^"\\])*)"|(\d+))""",
            ).find(raw)
                ?: return null
            return (match.groupValues[1].ifBlank { match.groupValues[2] })
                .trim()
                .takeIf(String::isNotBlank)
        }

        private fun extractStringField(raw: String, name: String): String? {
            val match = Regex(""""${Regex.escape(name)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
                .find(raw)
                ?: return null
            return match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim()
                .takeIf(String::isNotEmpty)
        }

        private val PROVIDER_CODE_REGEX = Regex("[A-Za-z0-9_.-]+")
        private val ERROR_FIELD_REGEX = Regex(
            """"error"\s*:\s*(?:\{\s*|"((?:\\.|[^"\\])*)")""",
            RegexOption.IGNORE_CASE,
        )
        private val TYPE_ERROR_REGEX = Regex(
            """"type"\s*:\s*"error"""",
            RegexOption.IGNORE_CASE,
        )
        private val STANDALONE_CODE_MESSAGE_REGEX = Regex(
            """"code"\s*:\s*(?:"(?:\\.|[^"\\])*"|\d+).*"message"\s*:""",
            RegexOption.IGNORE_CASE,
        )

        private data class ProviderErrorDetails(
            val code: String?,
            val type: String?,
            val message: String?,
        )
    }
}
