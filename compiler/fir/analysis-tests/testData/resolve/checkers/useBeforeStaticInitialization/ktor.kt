// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL

<!POSSIBLE_INITIALIZATION_DEADLOCK!>public sealed class RouteSelectorEvaluation(
    public val succeeded: Boolean
) {
    /**
     * A success result of a route evaluation against a call.
     *
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Success)
     *
     * @param quality indicates a quality of this route as compared to other sibling routes
     * @param parameters is an instance of [Parameters] with parameters filled by [RouteSelector]
     * @param segmentIncrement is a value indicating how many path segments has been consumed by a selector
     */
    <!POSSIBLE_INITIALIZATION_DEADLOCK!>public data class Success(
        public val quality: Double,
        public val segmentIncrement: Int = 0
    ) : RouteSelectorEvaluation(true)<!>

    /**
     * A failed result of a route evaluation against a call.
     *
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Failure)
     *
     * @param quality indicates a quality of this route as compared to other sibling routes
     * @param failureStatusCode response status code in case of failure.
     * Usually one of 400, 404, 405. Ignored on successful evaluation
     */
    <!POSSIBLE_INITIALIZATION_DEADLOCK!>public data class Failure(
        public val quality: Double,
        public val failureStatusCode: Int
    ) : RouteSelectorEvaluation(false)<!>

    public companion object {
        @Deprecated(
            "Please use RouteSelectorEvaluation.Failure() or RouteSelectorEvaluation.Success() constructors",
            level = DeprecationLevel.ERROR
        )
        public operator fun invoke(
            succeeded: Boolean,
            quality: Double,
            segmentIncrement: Int = 0
        ): RouteSelectorEvaluation = when (succeeded) {
            true -> RouteSelectorEvaluation.Success(quality, segmentIncrement)
            else -> RouteSelectorEvaluation.Failure(quality, 0)
        }

        /**
         * Quality of [RouteSelectorEvaluation] when a constant value is matched.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityConstant)
         */
        public const val qualityConstant: Double = 1.0

        /**
         * Quality of [RouteSelectorEvaluation] when a query parameter is matched.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityQueryParameter)
         */
        public const val qualityQueryParameter: Double = 1.0

        /**
         * Quality of [RouteSelectorEvaluation] when a parameter with prefix or suffix is matched.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityParameterWithPrefixOrSuffix)
         */
        public const val qualityParameterWithPrefixOrSuffix: Double = 0.9

        /**
         * Generic quality of [RouteSelectorEvaluation] to use as reference when some specific parameter is matched.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityParameter)
         */
        public const val qualityParameter: Double = 0.8

        /**
         * Quality of [RouteSelectorEvaluation] when a path parameter is matched.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityPathParameter)
         */
        public const val qualityPathParameter: Double = qualityParameter

        /**
         * Quality of [RouteSelectorEvaluation] when a HTTP method parameter is matched.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityMethodParameter)
         */
        @Suppress("unused")
        public const val qualityMethodParameter: Double = qualityParameter

        /**
         * Quality of [RouteSelectorEvaluation] when a wildcard is matched.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityWildcard)
         */
        public const val qualityWildcard: Double = 0.5

        /**
         * Quality of [RouteSelectorEvaluation] when an optional parameter is missing.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityMissing)
         */
        public const val qualityMissing: Double = 0.2

        /**
         * Quality of [RouteSelectorEvaluation] when a tailcard match is occurred.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityTailcard)
         */
        public const val qualityTailcard: Double = 0.1

        /**
         * Quality of [RouteSelectorEvaluation] that doesn't have its own quality but uses quality of its children.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityTransparent)
         */
        public const val qualityTransparent: Double = -1.0

        /**
         * Quality of [RouteSelectorEvaluation] when an HTTP method doesn't match.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityFailedMethod)
         */
        public const val qualityFailedMethod: Double = 0.02

        /**
         * Quality of [RouteSelectorEvaluation] when parameter (query, header, etc) doesn't match.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.qualityFailedParameter)
         */
        public const val qualityFailedParameter: Double = 0.01

        /**
         * Routing evaluation failed to succeed, route doesn't match a context.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.Failed)
         */
        public val Failed: RouteSelectorEvaluation.Failure =
            RouteSelectorEvaluation.Failure(0.0, 0)

        /**
         * Routing evaluation failed to succeed on a path selector.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.FailedPath)
         */
        public val FailedPath: RouteSelectorEvaluation.Failure =
            RouteSelectorEvaluation.Failure(0.0, 0)

        /**
         * Routing evaluation failed to succeed on a method selector.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.FailedMethod)
         */
        public val FailedMethod: RouteSelectorEvaluation.Failure =
            RouteSelectorEvaluation.Failure(qualityFailedMethod, 0)

        /**
         * Routing evaluation failed to succeed on a query, header, or other parameter selector.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.FailedParameter)
         */
        public val FailedParameter: RouteSelectorEvaluation.Failure =
            RouteSelectorEvaluation.Failure(qualityFailedParameter, 0)

        /**
         * Routing evaluation failed to succeed on Accept header.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.FailedAcceptHeader)
         */
        public val FailedAcceptHeader: RouteSelectorEvaluation.Failure =
            RouteSelectorEvaluation.Failure(qualityFailedParameter, 0)

        /**
         * Routing evaluation succeeded for a missing optional value.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.Missing)
         */
        public val Missing: RouteSelectorEvaluation =
            RouteSelectorEvaluation.Success(RouteSelectorEvaluation.qualityMissing)

        /**
         * Routing evaluation succeeded for a constant value.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.Constant)
         */
        public val Constant: RouteSelectorEvaluation =
            RouteSelectorEvaluation.Success(RouteSelectorEvaluation.qualityConstant)

        /**
         * Routing evaluation succeeded for a [qualityTransparent] value. Useful for helper DSL methods that may wrap
         * routes but should not change priority of routing.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.Transparent)
         */
        public val Transparent: RouteSelectorEvaluation =
            RouteSelectorEvaluation.Success(RouteSelectorEvaluation.qualityTransparent)

        /**
         * Routing evaluation succeeded for a single path segment with a constant value.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.ConstantPath)
         */
        public val ConstantPath: RouteSelectorEvaluation =
            RouteSelectorEvaluation.Success(RouteSelectorEvaluation.qualityConstant, segmentIncrement = 1)

        /**
         * Routing evaluation succeeded for a wildcard path segment.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.routing.RouteSelectorEvaluation.Companion.WildcardPath)
         */
        public val WildcardPath: RouteSelectorEvaluation =
            RouteSelectorEvaluation.Success(RouteSelectorEvaluation.qualityWildcard, segmentIncrement = 1)
    }
}<!>

public class CORSConfig {
    private val wildcardWithDot = "*."

    public companion object {

        /**
         * The default CORS max age value.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.plugins.cors.CORSConfig.Companion.CORS_DEFAULT_MAX_AGE)
         */
        public const val CORS_DEFAULT_MAX_AGE: Long = 24L * 3600 // 1 day

        /**
         * Default HTTP methods that are always allowed by CORS.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.plugins.cors.CORSConfig.Companion.CorsDefaultMethods)
         */
        public val CorsDefaultMethods: Set<<!UNRESOLVED_REFERENCE!>HttpMethod<!>> = <!UNRESOLVED_REFERENCE!>setOf<!>(<!UNRESOLVED_REFERENCE!>HttpMethod<!>.Get, <!UNRESOLVED_REFERENCE!>HttpMethod<!>.Post, <!UNRESOLVED_REFERENCE!>HttpMethod<!>.Head)

        /**
         * Default HTTP headers that are always allowed by CORS
         * (simple request headers according to https://www.w3.org/TR/cors/#simple-header).
         * Note that `Content-Type` header simplicity depends on its value.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.plugins.cors.CORSConfig.Companion.CorsSimpleRequestHeaders)
         */
        public val CorsSimpleRequestHeaders: Set<String> = caseInsensitiveSet(
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.Accept,
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.AcceptLanguage,
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.ContentLanguage,
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.ContentType
        )

        /**
         * Default HTTP headers that are always allowed by CORS to be used in a response
         * (simple request headers according to https://www.w3.org/TR/cors/#simple-header).
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.plugins.cors.CORSConfig.Companion.CorsSimpleResponseHeaders)
         */
        public val CorsSimpleResponseHeaders: Set<String> = caseInsensitiveSet(
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.CacheControl,
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.ContentLanguage,
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.ContentType,
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.Expires,
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.LastModified,
            <!UNRESOLVED_REFERENCE!>HttpHeaders<!>.Pragma
        )

        /**
         * The allowed set of content types that are allowed by CORS without preflight check.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.plugins.cors.CORSConfig.Companion.CorsSimpleContentTypes)
         */
        @Suppress("unused")
        public val CorsSimpleContentTypes: Set<<!UNRESOLVED_REFERENCE!>ContentType<!>> =
            <!UNRESOLVED_REFERENCE!>setOf<!>(
                <!UNRESOLVED_REFERENCE!>ContentType<!>.Application.FormUrlEncoded,
                <!UNRESOLVED_REFERENCE!>ContentType<!>.MultiPart.FormData,
                <!UNRESOLVED_REFERENCE!>ContentType<!>.Text.Plain
            ).unmodifiable()

        @OptIn(<!ANNOTATION_ARGUMENT_MUST_BE_CONST!><!UNRESOLVED_REFERENCE!>InternalAPI<!>::class<!>)
        private fun caseInsensitiveSet(vararg elements: String): Set<String> =
            <!UNRESOLVED_REFERENCE!>CaseInsensitiveSet<!>(elements.<!UNRESOLVED_REFERENCE!>asList<!>())
    }
}

public enum class ChannelOverflow {
    /**
     * Suspends the sender when the channel reaches capacity.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.websocket.ChannelOverflow.SUSPEND)
     */
    SUSPEND,

    /**
     * Closes the channel once it reaches capacity. Existing elements remain readable.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.websocket.ChannelOverflow.CLOSE)
     */
    CLOSE
}

/**
 * Thrown when a channel configured with [ChannelOverflow.CLOSE] exceeds its capacity.
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.websocket.ChannelOverflowException)
 *
 * @param message the detail message describing the overflow condition
 */
public class ChannelOverflowException(message: String) : RuntimeException(message)

/**
 * A configuration for a [kotlinx.coroutines.channels.Channel].
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.websocket.ChannelConfig)
 *
 * @property capacity: channel capacity.
 * @property onOverflow: overflow strategy.
 */
public class ChannelConfig internal constructor(
    public val capacity: Int,
    public val onOverflow: ChannelOverflow,
) {
    /**
     * Whether the channel can suspend when it reaches capacity.
     *
     * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.websocket.ChannelConfig.canSuspend)
     */
    public val canSuspend: Boolean
        get() = onOverflow == ChannelOverflow.SUSPEND && capacity != <!UNRESOLVED_REFERENCE!>Channel<!>.UNLIMITED

    public companion object {
        /**
         * A configuration with unlimited buffer.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.websocket.ChannelConfig.Companion.UNLIMITED)
         */
        public val UNLIMITED: ChannelConfig =
            ChannelConfig(capacity = <!UNRESOLVED_REFERENCE!>Channel<!>.UNLIMITED, onOverflow = ChannelOverflow.SUSPEND)
    }
}

<!POSSIBLE_INITIALIZATION_DEADLOCK!>public abstract class HttpCacheStorage {

    public companion object {
        /**
         * Disabled cache always empty and store nothing.
         *
         * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.client.plugins.cache.storage.HttpCacheStorage.Companion.Disabled)
         */
        <!POTENTIALLY_UNINITIALIZED_PROPERTY!>public val Disabled: HttpCacheStorage = <!POTENTIALLY_UNINITIALIZED_ACCESSES!>DisabledCacheStorage<!><!>
    }
}<!>

internal <!POSSIBLE_INITIALIZATION_DEADLOCK!>object DisabledCacheStorage<!> : HttpCacheStorage() {
}
