package com.gijiroku.benchmark

/** A deliberately sanitized cloud error: provider response bodies never reach the UI or history. */
class CloudProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
