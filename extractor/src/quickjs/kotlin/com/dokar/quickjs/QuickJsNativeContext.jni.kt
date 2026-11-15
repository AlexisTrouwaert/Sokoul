package com.dokar.quickjs

@ExperimentalQuickJsApi
class QuickJsNativeContext internal constructor(
    /** Address of the owning QuickJS context, valid only during the scoped callback. */
    val contextAddress: Long,
    /** Address of the owning QuickJS runtime, valid only during the scoped callback. */
    val runtimeAddress: Long,
)
