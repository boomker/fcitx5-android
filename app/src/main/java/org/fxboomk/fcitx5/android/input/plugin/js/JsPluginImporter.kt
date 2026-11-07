/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonPrimitive

/** 一次导入识别的结果。失败原因用枚举而不是字符串，界面据此给出可翻译的说明。 */
internal sealed interface JsPluginImportResult {

    data class Success(
        val meta: JsPluginMeta,
        val fieldCount: Int,
        /** 已有同名导入插件，保存会覆盖它（设置与密钥保留）。 */
        val replacesExisting: Boolean,
    ) : JsPluginImportResult

    data class Failure(
        val reason: Reason,
        /** 具体细节（脚本抛错信息、非法 id、被拒域名等），拼进提示里。 */
        val detail: String = "",
    ) : JsPluginImportResult {
        enum class Reason {
            TOO_LARGE,
            LOAD_FAILED,
            NO_PLUGIN,
            NO_VOICE_FEATURE,
            INVALID_ID,
            RESERVED_ID,
            NO_HOSTS,
        }
    }
}

/**
 * 导入策略：**不依赖 Android、不依赖 JS 引擎**的纯判定。
 *
 * 抽出来的理由和 [JsInvoker] 一样：单测必须能直接喂一份 meta 就验证「什么算合格插件」，
 * 而不是每次去起一个 QuickJS。
 */
internal object JsPluginImportPolicy {

    /**
     * 判定顺序有意如此：先形状（id 能不能用），再能力（语音或应用），最后边界（域名）。
     * 反过来的话，一个 id 都写错的脚本会先被抱怨「没声明域名」，用户改错地方。
     */
    fun validate(meta: JsPluginMeta, builtInIds: Set<String>): JsPluginImportResult.Failure.Reason? {
        if (!JsPluginMeta.isValidId(meta.id)) {
            return JsPluginImportResult.Failure.Reason.INVALID_ID
        }
        if (meta.providerId in builtInIds) {
            return JsPluginImportResult.Failure.Reason.RESERVED_ID
        }
        if (meta.voiceAsr == null && meta.apps.isEmpty()) {
            return JsPluginImportResult.Failure.Reason.NO_VOICE_FEATURE
        }
        if (meta.hosts.isEmpty() && meta.voiceAsr != null) {
            return JsPluginImportResult.Failure.Reason.NO_HOSTS
        }
        return null
    }
}

/**
 * 识别一个待导入的脚本：真的在 QuickJS 里跑一遍，而不是按关键字猜。
 *
 * 只有跑过才知道三件界面必须显示的事：这个脚本注册了哪些插件能力、它自报什么 id、
 * 它会连哪些域名。躲开求值就只能靠正则匹配源码，既漏报又会误报。
 */
internal object JsPluginImporter {

    suspend fun inspect(
        script: String,
        locale: String,
        builtInIds: Set<String>,
        importedIds: Set<String> = emptySet(),
    ): JsPluginImportResult {
        val size = script.toByteArray(Charsets.UTF_8).size
        if (size > JsPluginMeta.MAX_SCRIPT_BYTES) {
            return JsPluginImportResult.Failure(
                JsPluginImportResult.Failure.Reason.TOO_LARGE,
                size.toString(),
            )
        }
        val js = JsRuntime.create()
        return try {
            JsStubNatives.install(js, locale)
            runCatching { JsRuntime.initialize(js, JsPrelude.SOURCE + "\n" + script) }
                .getOrElse { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    return JsPluginImportResult.Failure(
                        JsPluginImportResult.Failure.Reason.LOAD_FAILED,
                        error.message.orEmpty(),
                    )
                }
            val meta = JsPluginMeta.parse(js.evaluate<String>("__meta()"))
                ?: return JsPluginImportResult.Failure(JsPluginImportResult.Failure.Reason.NO_PLUGIN)
            if (meta.id.isBlank()) {
                // 没有 register()，或者 register 的入参里没有 id：两者对用户都是「这不是插件」。
                return JsPluginImportResult.Failure(JsPluginImportResult.Failure.Reason.NO_PLUGIN)
            }
            JsPluginImportPolicy.validate(meta, builtInIds)?.let { reason ->
                return JsPluginImportResult.Failure(reason, meta.rejectedHosts.joinToString(", "))
            }
            // 字段数只是给用户看的「这个插件要填几项」；解析失败不算导入失败——
            // 设置页打不开字段是插件自己的问题，不该拦住导入。
            val fieldCount = runCatching {
                JsPluginFields.parse(js.evaluate<String>("__describe(" + JsonPrimitive(locale) + ")")).size
            }.getOrElse { if (it is CancellationException) throw it else 0 }
            JsPluginImportResult.Success(
                meta = meta,
                fieldCount = fieldCount,
                replacesExisting = meta.id in importedIds,
            )
        } finally {
            runCatching { js.close() }
        }
    }
}
