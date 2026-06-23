package com.wuyi.libraryauto.core.domain.model

enum class SignInError {
    NotInWindow,
    AlreadySignedIn,
    BeaconNotMatched,
    RateLimited,
    NetworkError,
    PermissionDenied,
    BluetoothDisabled,
    ServerRejected,
    Unknown,
}
