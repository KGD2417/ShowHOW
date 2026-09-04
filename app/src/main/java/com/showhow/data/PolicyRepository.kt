package com.showhow.data

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.showhow.core.Policy
import com.showhow.core.PolicyStore
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * The hot-reload strategy, and the reason this hackathon is survivable.
 *
 * For ten and a half hours we cannot compile, only push files. So:
 *   adb push policy.json /sdcard/ && adb shell run-as com.showhow \
 *       cp /sdcard/policy.json files/policy.json
 * ...and the app retunes live, no restart.
 *
 * assets/policy.json is the seed, once. After that filesDir/policy.json is the
 * only source of truth -- if we read assets we could never override it.
 */
class PolicyRepository(private val context: Context) {

    private val store = PolicyStore(File(context.filesDir, FILE_NAME)) { msg, t ->
        Log.w(TAG, msg, t)
    }

    val policy: StateFlow<Policy> get() = store.policy
    val lastError: String? get() = store.lastError

    private var observer: FileObserver? = null

    fun start() {
        store.seedAndLoad {
            context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
        }
        // Editors and adb replace rather than modify, so watch the directory
        // and filter, not the file -- a watch on the inode dies with the inode.
        observer = object : FileObserver(context.filesDir, CLOSE_WRITE or MOVED_TO or MODIFY) {
            override fun onEvent(event: Int, path: String?) {
                if (path == FILE_NAME) store.reload()
            }
        }.also { it.startWatching() }
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    companion object {
        private const val TAG = "PolicyRepository"
        const val FILE_NAME = "policy.json"
    }
}
