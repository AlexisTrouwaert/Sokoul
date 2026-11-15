package com.dokar.quickjs

import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.JsObjectHandle
import com.dokar.quickjs.binding.ObjectBinding
import com.dokar.quickjs.converter.TypeConverter
import com.dokar.quickjs.converter.TypeConverters
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * DSL for [QuickJs]. The instance will be closed automatically when the [block] is finished.
 *
 * The dispatcher of the current coroutine context (or throw if not found) will be used as
 * the job dispatcher.
 */
@OptIn(ExperimentalStdlibApi::class)
suspend inline fun <T : Any?> quickJs(block: QuickJs.() -> T): T {
    val dispatcher = coroutineContext[CoroutineDispatcher]
        ?: throw UnsupportedOperationException(
            "The current coroutine context does not have a coroutine context. " +
                    "Please pass your dispatcher explicitly using another function."
        )
    return quickJs(dispatcher, block)
}

/**
 * DSL for a [QuickJs] runtime backed by [moduleLoader]. The instance is closed
 * automatically when [block] finishes.
 *
 * The dispatcher from the current coroutine context is used for async jobs.
 *
 * @param moduleLoader The runtime-scoped ES module loader.
 */
@OptIn(ExperimentalStdlibApi::class)
suspend inline fun <T : Any?> quickJs(
    moduleLoader: ModuleLoader,
    block: QuickJs.() -> T,
): T {
    val dispatcher = coroutineContext[CoroutineDispatcher]
        ?: throw UnsupportedOperationException(
            "The current coroutine context does not have a coroutine context. " +
                    "Please pass your dispatcher explicitly using another function."
        )
    return quickJs(dispatcher, moduleLoader, block)
}

/**
 * DSL for [QuickJs]. The instance will be closed automatically when the [block] is finished.
 *
 * @param jobDispatcher The dispatcher for executing async jobs.
 */
inline fun <T : Any?> quickJs(jobDispatcher: CoroutineDispatcher, block: QuickJs.() -> T): T {
    val quickJs = QuickJs.create(jobDispatcher = jobDispatcher)
    return try {
        quickJs.block()
    } finally {
        quickJs.close()
    }
}

/**
 * DSL for a [QuickJs] runtime backed by [moduleLoader]. The instance is closed
 * automatically when [block] finishes.
 *
 * @param jobDispatcher The dispatcher for executing async jobs.
 * @param moduleLoader The runtime-scoped ES module loader.
 */
inline fun <T : Any?> quickJs(
    jobDispatcher: CoroutineDispatcher,
    moduleLoader: ModuleLoader,
    block: QuickJs.() -> T,
): T {
    val quickJs = QuickJs.create(
        jobDispatcher = jobDispatcher,
        moduleLoader = moduleLoader,
    )
    return try {
        quickJs.block()
    } finally {
        quickJs.close()
    }
}

/**
 * The QuickJS runtime.
 */
