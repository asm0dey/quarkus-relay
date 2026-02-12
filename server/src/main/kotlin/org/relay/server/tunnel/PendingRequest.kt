package org.relay.server.tunnel

import org.relay.shared.protocol.ResponsePayload
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Class for tracking in-flight requests waiting for a response.
 * Manages the lifecycle of a pending request including completion and timeout handling.
 *
 * @property correlationId The unique identifier for correlating requests and responses
 * @property responseFuture The CompletableFuture that will be completed with the response
 * @property timeout The scheduled timeout task that can be cancelled if response arrives in time
 */
class PendingRequest(
    val correlationId: String,
    val responseFuture: CompletableFuture<ResponsePayload>,
    val timeout: ScheduledFuture<*>
) {
    /**
     * Checks if this pending request has been completed (either successfully or exceptionally)
     * or was cancelled.
     */
    fun isComplete(): Boolean = responseFuture.isDone

    /**
     * Completes the request with the given response payload.
     * Cancels the timeout task if still pending.
     *
     * @param response The response payload to complete the future with
     * @return true if the request was successfully completed, false if already complete
     */
    fun complete(response: ResponsePayload): Boolean {
        timeout.cancel(false)
        return responseFuture.complete(response)
    }

    /**
     * Completes the request exceptionally with the given exception.
     * Cancels the timeout task if still pending.
     *
     * @param exception The exception to complete the future with
     * @return true if the request was successfully completed exceptionally, false if already complete
     */
    fun completeExceptionally(exception: Throwable): Boolean {
        timeout.cancel(false)
        return responseFuture.completeExceptionally(exception)
    }

    /**
     * Times out the request if it hasn't been completed yet.
     * This method is typically called by the scheduled executor when timeout expires.
     *
     * @return true if the request was timed out by this call, false if already complete
     */
    fun timeout(): Boolean {
        return responseFuture.completeExceptionally(
            RequestTimeoutException("Request $correlationId timed out waiting for response")
        )
    }

    /**
     * Cancels the pending request.
     *
     * @param mayInterruptIfRunning whether the timeout task should be interrupted
     * @param reason optional reason for cancellation
     * @return true if the request was cancelled by this call
     */
    fun cancel(mayInterruptIfRunning: Boolean = false, reason: String? = null): Boolean {
        timeout.cancel(mayInterruptIfRunning)
        return if (reason != null) {
            responseFuture.completeExceptionally(RequestCancelledException(reason))
        } else {
            responseFuture.cancel(mayInterruptIfRunning)
        }
    }

    /**
     * Returns the timeout remaining for this request in the given time unit.
     *
     * @param unit the time unit for the result
     * @return the time remaining in the specified unit, or 0 if already done
     */
    fun getDelay(unit: TimeUnit): Long {
        return if (responseFuture.isDone) 0 else timeout.getDelay(unit)
    }
}

/**
 * Exception thrown when a pending request times out.
 */
class RequestTimeoutException(message: String) : RuntimeException(message)

/**
 * Exception thrown when a pending request is cancelled.
 */
class RequestCancelledException(message: String) : RuntimeException(message)
