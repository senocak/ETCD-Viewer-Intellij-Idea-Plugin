package com.github.senocak.etcd.client

/**
 * Any failure talking to etcd, already phrased for display in a balloon or dialog.
 *
 * [grpcCode] carries etcd's gRPC status code when the server answered with a structured error
 * (16 = Unauthenticated, 7 = PermissionDenied, 3 = InvalidArgument), and is null for transport
 * level failures such as a refused connection.
 */
class EtcdException(
    message: String,
    cause: Throwable? = null,
    val grpcCode: Int? = null,
    val httpStatus: Int? = null,
) : RuntimeException(message, cause) {

    val isAuthTokenRejected: Boolean
        get() = httpStatus == 401 ||
            grpcCode == GRPC_UNAUTHENTICATED ||
            message.orEmpty().contains("invalid auth token", ignoreCase = true)

    val isPermissionDenied: Boolean
        get() = grpcCode == GRPC_PERMISSION_DENIED ||
            message.orEmpty().contains("permission denied", ignoreCase = true)

    companion object {
        const val GRPC_PERMISSION_DENIED = 7
        const val GRPC_UNAUTHENTICATED = 16
    }
}
