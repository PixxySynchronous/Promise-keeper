package com.satyam.whatsappbot.domain

import android.content.Context
import android.util.JsonReader
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStreamReader
import java.util.Base64
import java.util.Locale

data class SemanticResult(val isCommitment: Boolean, val confidence: Float)

class OnDeviceSemanticClassifier(context: Context) {
    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var tokenizer: LocalUnigramTokenizer? = null
    private var head: CommitmentHead? = null

    fun classify(message: String): SemanticResult? = runCatching {
        load()
        val hidden = hiddenStates(message) ?: return null
        val activeHead = head ?: return null
        val probability = activeHead.probability(hidden)
        SemanticResult(probability >= activeHead.threshold, probability)
    }.onFailure { Log.w("PromiseKeeperAI", "Local semantic inference unavailable", it) }.getOrNull()

    fun warmUp() {
        runCatching { load() }.onFailure { Log.w("PromiseKeeperAI", "Local semantic model failed to load", it) }
    }

    private fun load() {
        if (session != null) return
        tokenizer = LocalUnigramTokenizer(appContext)
        head = loadHead()
        // Loading from a file path keeps the 118 MB model out of the Java heap (ONNX Runtime reads it natively),
        // which matters on phones with a small per-app heap limit.
        session = environment.createSession(modelOnDisk().path, OrtSession.SessionOptions())
    }

    /**
     * ONNX Runtime cannot read from inside the APK, so the model is copied once to app-private storage
     * (noBackupFilesDir: excluded from backups) and copied again only after the app is updated.
     */
    private fun modelOnDisk(): File {
        val directory = File(appContext.noBackupFilesDir, "semantic").apply { mkdirs() }
        val model = File(directory, MODEL_ASSET.substringAfterLast('/'))
        val stamp = File(directory, "model.version")
        val installed = appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString()
        if (model.exists() && stamp.exists() && stamp.readText() == installed) return model
        val partial = File(directory, model.name + ".partial")
        appContext.assets.open(MODEL_ASSET).use { input -> partial.outputStream().use { input.copyTo(it, 1 shl 16) } }
        check(partial.renameTo(model)) { "could not move model into place" }
        stamp.writeText(installed)
        return model
    }

    // Weights produced by tools/train_semantic_head.py.
    private fun loadHead(): CommitmentHead {
        var weights = FloatArray(0)
        var bias = 0f
        var threshold = 0.5f
        JsonReader(InputStreamReader(appContext.assets.open("semantic/commitment_head.json"), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "weights" -> {
                        val values = ArrayList<Float>()
                        reader.beginArray()
                        while (reader.hasNext()) values += reader.nextDouble().toFloat()
                        reader.endArray()
                        weights = values.toFloatArray()
                    }
                    "bias" -> bias = reader.nextDouble().toFloat()
                    "threshold" -> threshold = reader.nextDouble().toFloat()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return CommitmentHead(weights, bias, threshold)
    }

    private companion object {
        const val MODEL_ASSET = "semantic/model_qint8_arm64.onnx"
    }

    private fun hiddenStates(text: String): List<FloatArray>? {
        val activeSession = session ?: return null
        // Lowercased, as in training: the model is case-sensitive and keyboards capitalise the first word ("Kal karta hu").
        val encoded = tokenizer?.encode(text.lowercase(Locale.ROOT)) ?: return null
        val ids = OnnxTensor.createTensor(environment, arrayOf(encoded.ids))
        val mask = OnnxTensor.createTensor(environment, arrayOf(encoded.mask))
        val types = OnnxTensor.createTensor(environment, arrayOf(encoded.types))
        try {
            activeSession.run(mapOf("input_ids" to ids, "attention_mask" to mask, "token_type_ids" to types)).use { result ->
                val batch = result[0].value as? Array<*> ?: return null
                val sequence = batch.firstOrNull() as? Array<*> ?: return null
                return sequence.map { it as? FloatArray ?: return null }.takeIf { it.isNotEmpty() }
            }
        } finally {
            ids.close(); mask.close(); types.close()
        }
    }
}

private data class Encoded(val ids: LongArray, val mask: LongArray, val types: LongArray)

/** Reads the bundled tokenizer.json from APK assets; tokenization itself is in [SentencePieceTokenizer]. */
private class LocalUnigramTokenizer(context: Context) {
    private val tokenizer: SentencePieceTokenizer

    init {
        val pieces = ArrayList<String>(250_002)
        val scores = ArrayList<Double>(250_002)
        val specials = HashMap<String, Int>()
        var unkId = 3
        var charsmap = ByteArray(0)
        JsonReader(InputStreamReader(context.assets.open("semantic/tokenizer.json"), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "model" -> reader.readObject { name ->
                        when (name) {
                            "unk_id" -> unkId = reader.nextInt()
                            "vocab" -> reader.readArray {
                                reader.beginArray()
                                pieces += reader.nextString()
                                scores += reader.nextDouble()
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    "normalizer" -> reader.readObject { name ->
                        if (name == "precompiled_charsmap") charsmap = Base64.getDecoder().decode(reader.nextString()) else reader.skipValue()
                    }
                    "added_tokens" -> reader.readArray {
                        var id = -1
                        var content = ""
                        var special = false
                        reader.readObject { name ->
                            when (name) {
                                "id" -> id = reader.nextInt()
                                "content" -> content = reader.nextString()
                                "special" -> special = reader.nextBoolean()
                                else -> reader.skipValue()
                            }
                        }
                        if (special) specials[content] = id
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        tokenizer = SentencePieceTokenizer(pieces, scores.toDoubleArray(), unkId, charsmap, specials)
    }

    fun encode(text: String): Encoded {
        val ids = tokenizer.encode(text)
        return Encoded(LongArray(ids.size) { ids[it].toLong() }, LongArray(ids.size) { 1L }, LongArray(ids.size))
    }

    private inline fun JsonReader.readObject(onField: (String) -> Unit) {
        beginObject()
        while (hasNext()) onField(nextName())
        endObject()
    }

    private inline fun JsonReader.readArray(onItem: () -> Unit) {
        beginArray()
        while (hasNext()) onItem()
        endArray()
    }
}