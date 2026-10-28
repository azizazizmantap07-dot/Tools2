package io.nekohasekai.sfa.vendor

sealed class PackageQueryStrategy {
    data object Root : PackageQueryStrategy()
    data object Direct : PackageQueryStrategy()
}
