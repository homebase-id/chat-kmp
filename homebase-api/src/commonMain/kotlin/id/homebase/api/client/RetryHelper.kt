package id.homebase.api.client

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Configuration for retry behavior.
 *
 * @param maxRetries Maximum number of retry attempts (excluding the initial attempt)
 * @param initialDelayMs Initial delay before the first retry in milliseconds
 * @param maxDelayMs Maximum delay between retries in milliseconds
 * @param backoffMultiplier Multiplier applied to delay after each retry (for exponential backoff)
 * @param retryOn Optional predicate to determine if a specific exception should trigger a retry
 */
data class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 10000L,
    val backoffMultiplier: Double = 2.0,
    val retryOn: ((Exception) -> Boolean)? = null
)

/** Result wrapper for retry operations that provides detailed outcome information. */
sealed class RetryResult<out T> {
    data class Success<T>(val value: T) : RetryResult<T>()
    data class Failure(val exception: Exception, val attempts: Int) : RetryResult<Nothing>()

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw exception
    }

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (Exception, Int) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(exception, attempts)
    }
}

/**
 * Executes a suspending block with retry logic and exponential backoff.
 *
 * - CancellationException is always rethrown immediately (never retried)
 * - Returns null if all retries are exhausted and block returns null
 * - Uses exponential backoff with configurable multiplier and max delay
 *
 * @param config Retry configuration
 * @param tag Optional tag for logging
 * @param block The suspending block to execute
 * @return The result of the block, or null if all retries failed
 */
suspend fun <T> withRetry(
    config: RetryConfig = RetryConfig(), tag: String? = null, block: suspend (attempt: Int) -> T?
): T? {
    return withRetryResult(config, tag, block).getOrNull()
}

/**
 * Executes a suspending block with retry logic, returning a detailed [RetryResult].
 *
 * Use this variant when you need to know whether the operation succeeded or failed, and how many
 * attempts were made.
 *
 * @param config Retry configuration
 * @param tag Optional tag for logging
 * @param block The suspending block to execute
 * @return [RetryResult] containing either the success value or failure details
 */
suspend fun <T> withRetryResult(
    config: RetryConfig = RetryConfig(), tag: String? = null, block: suspend (attempt: Int) -> T?
): RetryResult<T?> {
    val logTag = tag ?: "RetryHelper"
    var lastException: Exception? = null
    var currentDelay = config.initialDelayMs

    repeat(config.maxRetries + 1) { attempt ->
        try {
            val result = block(attempt)
            if (result != null) {
                if (attempt > 0) {
                    Logger.d(logTag) { "Succeeded on attempt ${attempt + 1}" }
                }
                return RetryResult.Success(result)
            }
            // Result was null - treat as retriable failure
            if (attempt < config.maxRetries) {
                Logger.w(logTag) {
                    "Attempt ${attempt + 1}/${config.maxRetries + 1} returned null, retrying in ${currentDelay}ms"
                }
                delay(currentDelay)
                currentDelay = (currentDelay * config.backoffMultiplier).toLong()
                    .coerceAtMost(config.maxDelayMs)
            }
        } catch (e: CancellationException) {
            // Never retry on cancellation - rethrow immediately
            throw e
        } catch (e: Exception) {
            lastException = e

            // Check if we should retry this specific exception
            val shouldRetry = config.retryOn?.invoke(e) ?: true
            if (!shouldRetry) {
                Logger.e(logTag) { "Non-retriable exception: ${e.message}" }
                return RetryResult.Failure(e, attempt + 1)
            }

            if (attempt < config.maxRetries) {
                Logger.w(logTag) {
                    "Attempt ${attempt + 1}/${config.maxRetries + 1} failed: ${e.message}, retrying in ${currentDelay}ms"
                }
                delay(currentDelay)
                currentDelay = (currentDelay * config.backoffMultiplier).toLong()
                    .coerceAtMost(config.maxDelayMs)
            } else {
                Logger.e(logTag) { "All ${config.maxRetries + 1} attempts failed: ${e.message}" }
            }
        }
    }

    return if (lastException != null) {
        RetryResult.Failure(lastException!!, config.maxRetries + 1)
    } else {
        // All attempts returned null
        RetryResult.Success(null)
    }
}

/** Convenience extension for simple retry with default config. */
suspend fun <T> (suspend () -> T?).withRetry(maxRetries: Int = 3, tag: String? = null): T? =
    withRetry(RetryConfig(maxRetries = maxRetries), tag) { this() }
