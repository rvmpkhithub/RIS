package com.ris.imagedistributor.domain

/**
 * Repository-layer result type. Repositories catch IOException/HttpException at their boundary
 * and translate to this; nothing above the repository layer sees a raw exception. [AD-8]
 */
sealed class AppResult<out T> {
    data class Success<out T>(val value: T) : AppResult<T>()
    data class Failure(val reason: FailureReason) : AppResult<Nothing>()
}

enum class FailureReason {
    NETWORK_UNREACHABLE,
    SERVER_ERROR,
    DATABASE_ERROR,
    INVALID_INPUT,
    UNKNOWN,
    FILE_ERROR,
}
