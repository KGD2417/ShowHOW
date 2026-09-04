package com.showhow.core

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the one live [Policy] the whole app reads from.
 *
 * Pure java.io, no android imports, so the reload path is a JVM test. The
 * FileObserver that calls [reload] lives in data/PolicyRepository.
 *
 * The contract that matters at 3am: a broken policy.json never crashes and
 * never silently resets the tuning -- it keeps the last good values and tells
 * you what it choked on.
 */
class PolicyStore(
    val file: File,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val _policy = MutableStateFlow(Policy.DEFAULT)
    val policy: StateFlow<Policy> = _policy.asStateFlow()

    var lastError: String? = null
        private set

    /** Copy the shipped default into place the first time only. Then load. */
    fun seedAndLoad(default: () -> String) {
        if (!file.exists()) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(default())
            }.onFailure { onError("could not seed ${file.name}", it) }
        }
        reload()
    }

    /** Re-read the file. Keeps the previous value if the new one is unusable. */
    fun reload(): Boolean {
        val text = runCatching { file.readText() }.getOrElse {
            fail("could not read ${file.name}: ${it.message}", it)
            return false
        }
        val parsed = runCatching { Policy.parse(text) }.getOrElse {
            fail("bad policy.json, keeping previous: ${it.message}", it)
            return false
        }
        lastError = null
        _policy.value = parsed
        return true
    }

    private fun fail(msg: String, t: Throwable) {
        lastError = msg
        onError(msg, t)
    }
}
