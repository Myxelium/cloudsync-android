package dev.cloudsync.core.error

open class CloudSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AuthenticationException(message: String, cause: Throwable? = null) : CloudSyncException(message, cause)

class RemoteChangedException(message: String) : CloudSyncException(message)

class ConflictResponseException(message: String) : CloudSyncException(message)

class NotFoundException(message: String) : CloudSyncException(message)

class InsufficientStorageException(message: String) : CloudSyncException(message)

class PermissionException(message: String) : CloudSyncException(message)

class ServerUnavailableException(message: String, cause: Throwable? = null) : CloudSyncException(message, cause)

class InvalidNameException(message: String) : CloudSyncException(message)

class NetworkException(message: String, cause: Throwable? = null) : CloudSyncException(message, cause)

class ValidationException(message: String) : CloudSyncException(message)

class MergeAbortedException(message: String) : CloudSyncException(message)

fun isRetryable(error: Throwable): Boolean = when (error) {
    is NetworkException -> true
    is ServerUnavailableException -> true
    is AuthenticationException -> false
    is RemoteChangedException -> false
    is ConflictResponseException -> false
    is NotFoundException -> false
    is InsufficientStorageException -> false
    is PermissionException -> false
    is InvalidNameException -> false
    is ValidationException -> false
    is MergeAbortedException -> false
    else -> false
}

fun isRetryableStatus(code: Int): Boolean = code == 408 || code == 423 || code == 425 || code == 429 || code in 500..599
