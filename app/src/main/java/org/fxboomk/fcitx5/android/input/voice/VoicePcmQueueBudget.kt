/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.voice

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 语音 PCM 队列的容量预算换算。
 *
 * 队列容量以"最大可缓存音频时长（毫秒）"表达，而不是任意包数或字节数：过载判定必须与
 * "积压了多少秒音频"成正比。同一个包数常量换个采样率或换个包长，代表的延迟就完全不同，
 * 而积压音频超过几百毫秒后识别结果已经不是用户当前说的那句话了。
 *
 * 这里只做纯换算，不引用任何 Android 类型，便于 JVM 单测（见 VoicePcmQueueBudgetTest）。
 */
internal object VoicePcmQueueBudget {

    /**
     * 采集层每个 PCM 包的时长。
     *
     * VoiceInputAudioCapture 按 `sampleRate / 10` 个采样切包，因此无论采样率是多少，
     * 一个包都恰好是 100ms 的音频（16kHz → 1600 采样；48kHz → 4800 采样）。
     */
    const val PACKET_DURATION_MS = 100L

    /** 单个 PCM 包的字节数：每包采样数 × 字节/采样 × 声道数（16bit 单声道即 2 字节/采样）。 */
    fun packetBytes(sampleRate: Int, bitsPerSample: Int, channels: Int): Int {
        val samples = (sampleRate / 10).coerceAtLeast(1)
        val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
        return samples * bytesPerSample * channels.coerceAtLeast(1)
    }

    /**
     * 时长预算 → 队列容量（包数）。
     *
     * `+1` 是窗口首尾两个端点包：与预滚窗口的淘汰判定（"年龄 > 预算"）一致，
     * 预算窗口内最多可以同时保留首尾两个端点包。
     *
     * 结果至少为 1：容量 0 的 Channel 会让 `trySend` 永远失败，
     * 把每一次入队都误判成过载。
     */
    fun capacityPackets(budgetMs: Long, packetDurationMs: Long = PACKET_DURATION_MS): Int {
        val packet = packetDurationMs.coerceAtLeast(1L)
        val budget = budgetMs.coerceAtLeast(0L)
        return ((budget + packet - 1) / packet).toInt() + 1
    }

    /** 时长预算对应的最大缓存字节数，用于日志/内存上界的说明。 */
    fun bufferedBytes(sampleRate: Int, bitsPerSample: Int, channels: Int, budgetMs: Long): Int =
        capacityPackets(budgetMs) * packetBytes(sampleRate, bitsPerSample, channels)
}

/**
 * 语音 PCM 队列的运行时统计。
 *
 * 生产者是 AudioRecord 采集线程，消费者是 feed 协程，两侧并发读写，所以计数器全部无锁
 * （原子类型），生产端只做几次原子加法，绝不阻塞采集线程。
 *
 * 队列深度不读 Channel 内部状态（Channel 不对外暴露 size）：这里把"已入队但 feedAudio
 * 尚未返回的包数"当作深度——生产者入队 +1，消费者 feed 完成后 -1，因此高水位就是真实
 * 积压包数。
 *
 * "最旧包年龄"的语义：队首包从入队起等待了多久。只在入队采样点测算（队列延迟监控的
 * 常规做法）：pts 与采集起点同一时间轴，新包入队时 `pts - 队首包 pts` 就是队首包已经
 * 等待的时间，误差不超过一个包；深度归零后重新锚定队首。
 */
internal class VoicePcmQueueMetrics {

    private val depth = AtomicInteger(0)
    private val feedCalls = AtomicInteger(0)
    private val slowFeedCalls = AtomicInteger(0)
    private val overloadCount = AtomicInteger(0)
    private val maxDepthCount = AtomicInteger(0)
    private val maxFeedDurationMs = AtomicLong(0L)
    private val maxHeadAge = AtomicLong(0L)

    @Volatile private var headPtsMs = 0L

    /** 队列深度高水位（包数）。 */
    val maxDepth: Int get() = maxDepthCount.get()

    /** 最旧包年龄高水位（ms）。 */
    val maxHeadAgeMs: Long get() = maxHeadAge.get()

    /** 单次 Binder feedAudio 的最长耗时（ms）。 */
    val maxFeedMs: Long get() = maxFeedDurationMs.get()

    /** feedAudio 调用次数。 */
    val calls: Int get() = feedCalls.get()

    /** 单次 Binder feedAudio 超过调用方阈值（VoiceInputProviderManager.FEED_SLOW_WARN_MS）的次数。 */
    val slowCalls: Int get() = slowFeedCalls.get()

    /** 队列满导致的中止次数。 */
    val overloads: Int get() = overloadCount.get()

    /** 新会话开始时清零，避免上一个会话的高水位混进本次统计。 */
    fun reset() {
        depth.set(0)
        feedCalls.set(0)
        slowFeedCalls.set(0)
        overloadCount.set(0)
        maxDepthCount.set(0)
        maxFeedDurationMs.set(0L)
        maxHeadAge.set(0L)
        headPtsMs = 0L
    }

    /** 生产者在 [kotlinx.coroutines.channels.Channel.trySend] 成功后调用。 */
    fun onEnqueued(ptsMs: Long) {
        val current = depth.incrementAndGet()
        if (current == 1) headPtsMs = ptsMs
        val age = (ptsMs - headPtsMs).coerceAtLeast(0L)
        raiseMax(maxDepthCount, current)
        raiseMax(maxHeadAge, age)
    }

    /** 消费者完成一次 feedAudio 后调用。 */
    fun onFed(durationMs: Long) {
        releaseDepth()
        feedCalls.incrementAndGet()
        raiseMax(maxFeedDurationMs, durationMs)
    }

    /** 消费者因为 provider 已消失而跳过一个包（未调用 feedAudio）时调用。 */
    fun onFedSkipped() {
        releaseDepth()
    }

    /** 队列满（provider 处理不过来）时调用，返回累计过载中止次数。 */
    fun onOverflow(): Int = overloadCount.incrementAndGet()

    /** 记录一次慢调用（耗时超过调用方阈值）。 */
    fun onSlowFeed() {
        slowFeedCalls.incrementAndGet()
    }

    /** 单行汇总，供日志使用。 */
    fun summary(): String =
        "queueMaxDepth=$maxDepth oldestQueuedMaxAgeMs=$maxHeadAgeMs " +
            "feedCalls=$calls feedMaxMs=$maxFeedMs slowFeedCalls=$slowFeedCalls overloads=$overloads"

    private fun releaseDepth() {
        depth.updateAndGet { d -> if (d > 0) d - 1 else 0 }
    }

    /** 无锁抬高高水位：无 CAS 循环，避免在同一时间轴上多线程竞争。 */
    private fun raiseMax(target: AtomicInteger, candidate: Int) {
        if (candidate > target.get()) target.set(candidate)
    }

    private fun raiseMax(target: AtomicLong, candidate: Long) {
        if (candidate > target.get()) target.set(candidate)
    }
}