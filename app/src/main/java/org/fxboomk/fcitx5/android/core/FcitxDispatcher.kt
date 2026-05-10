/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class FcitxDispatcher(private val controller: FcitxController) : CoroutineDispatcher() {

    class WrappedRunnable(private val runnable: Runnable) : Runnable by runnable {
        private val time = System.currentTimeMillis()

        override fun run() {
            val delta = System.currentTimeMillis() - time
            if (delta > JOB_WAITING_LIMIT) {
                Timber.w("$this has waited $delta ms to get run since created!")
            }
            runnable.run()
        }

        override fun toString(): String = "WrappedRunnable[${hashCode()}]"
    }

    // this is fcitx main thread
    private val internalDispatcher = Executors.newSingleThreadExecutor {
        Thread(it).apply {
            name = "fcitx-main"
        }
    }.asCoroutineDispatcher()

    private val internalScope = CoroutineScope(internalDispatcher)

    interface FcitxController {
        fun nativeStartup()
        fun nativeLoopOnce()
        fun nativeScheduleEmpty()
        fun nativeExit()
    }

    private val runningLock = Mutex()

    private val queue = ConcurrentLinkedQueue<WrappedRunnable>()

    private val isRunning = AtomicBoolean(false)
    private val isAcceptingJobs = AtomicBoolean(false)
    private val isNativeReady = AtomicBoolean(false)

    /**
     * Start the dispatcher
     * This function returns immediately
     */
    fun start() {
        Timber.d("FcitxDispatcher start()")
        if (!isAcceptingJobs.compareAndSet(false, true)) {
            Timber.w("Skip start: dispatcher is already active")
            return
        }
        isRunning.set(true)
        internalScope.launch {
            runningLock.withLock {
                if (isRunning.get()) {
                    Timber.d("nativeStartup()")
                    try {
                        controller.nativeStartup()
                        isNativeReady.set(true)
                        controller.nativeScheduleEmpty()
                        while (isActive && isRunning.get()) {
                            controller.nativeLoopOnce()
                            while (true) {
                                val block = queue.poll() ?: break
                                block.run()
                            }
                        }
                    } finally {
                        isNativeReady.set(false)
                        isRunning.set(false)
                        isAcceptingJobs.set(false)
                        Timber.i("nativeExit()")
                        controller.nativeExit()
                    }
                }
            }
        }
    }

    /**
     * Stop the dispatcher
     * This function blocks until fully stopped
     */
    fun stop(): List<Runnable> {
        Timber.i("FcitxDispatcher stop()")
        isAcceptingJobs.set(false)
        return if (isRunning.compareAndSet(true, false)) {
            runBlocking {
                if (isNativeReady.get()) {
                    controller.nativeScheduleEmpty()
                }
                runningLock.withLock {
                    val rest = queue.toList()
                    queue.clear()
                    isNativeReady.set(false)
                    rest
                }
            }
        } else emptyList()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!isAcceptingJobs.get()) {
            if (context[Job]?.isActive == false) {
                Timber.d("Drop runnable from cancelled context while dispatcher is stopped: $block")
                return
            }
            throw IllegalStateException("Dispatcher is not in running state!")
        }
        queue.offer(WrappedRunnable(block))
        if (isNativeReady.get()) {
            controller.nativeScheduleEmpty()
        }
    }

    companion object {
        const val JOB_WAITING_LIMIT = 2000L
    }

}
