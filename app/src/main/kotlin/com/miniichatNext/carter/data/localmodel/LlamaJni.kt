/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.localmodel

internal object LlamaJni {
    init { System.loadLibrary("mcn_llama") }

    external fun isValidModel(path: ByteArray): Boolean
    external fun newCancellation(): Long
    external fun cancel(handle: Long)
    external fun freeCancellation(handle: Long)
    external fun generate(
        cancellation: Long, path: ByteArray, roles: Array<String>, messages: Array<ByteArray>,
        context: Int, threads: Int, maxTokens: Int, temperature: Float, topP: Float,
        topK: Int, repeatPenalty: Float, seed: Int, output: LocalTokenSink,
    ): IntArray
}
