package dev.lukag.lkml.core

/**
 * The single result wrapper crossing the data → domain → UI boundary.
 *
 * [Offline] is a first-class outcome rather than an error: on a local-first app,
 * "here is cached data and we could not refresh it" is a success case with a caveat,
 * and the UI should show the data plus a banner rather than an error screen.
 */
sealed interface Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>
    data class Loading<T>(val cached: T? = null) : Resource<T>
    data class Offline<T>(val cached: T?) : Resource<T>
    data class Error<T>(val error: AppError, val cached: T? = null) : Resource<T>

    val dataOrNull: T?
        get() = when (this) {
            is Success -> data
            is Loading -> cached
            is Offline -> cached
            is Error -> cached
        }
}

/** Errors the UI knows how to phrase. Keeps `Throwable` out of the presentation layer. */
sealed interface AppError {
    /** No usable connectivity, or the request timed out at the socket level. */
    data class Network(val cause: String?) : AppError

    /** Server answered, but not with success. */
    data class Http(val code: Int, val message: String?) : AppError

    /**
     * lore.kernel.org answered `200 text/html` with a bot-interstitial instead of data.
     * Distinct from [Parse] because the fix is a request-header problem, not a data problem.
     */
    data object BotChallenge : AppError

    /** The response arrived but did not look like what the endpoint promised. */
    data class Parse(val what: String) : AppError

    /** Room / disk failures. */
    data class Storage(val cause: String?) : AppError

    data class Unknown(val cause: String?) : AppError
}

inline fun <T, R> Resource<T>.map(transform: (T) -> R): Resource<R> = when (this) {
    is Resource.Success -> Resource.Success(transform(data))
    is Resource.Loading -> Resource.Loading(cached?.let(transform))
    is Resource.Offline -> Resource.Offline(cached?.let(transform))
    is Resource.Error -> Resource.Error(error, cached?.let(transform))
}
