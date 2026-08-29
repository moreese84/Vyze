package com.vyze.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity storing a completed VLM interaction for adaptive intelligence.
 *
 * Each record captures the full context of a past inference:
 * - The image embedding (lightweight 16×16 grayscale pixel vector) for similarity search
 * - The raw prompt that was sent to the model
 * - The generated output for contextual reference
 * - Optional user feedback (edited text, preference tags)
 * - Timestamp for recency weighting
 *
 * ## Embedding Format
 * [imageEmbedding] is a ByteArray-serialized FloatArray of 256 floats (16×16 grayscale pixels,
 * normalized to 0.0–1.0). This is computed by [com.vyze.app.embedding.EmbeddingEngine]
 * and provides lightweight visual similarity without requiring a separate ML model.
 *
 * ## Retrieval
 * [com.vyze.app.memory.MemoryRepository.findSimilar] loads all embeddings into memory,
 * computes cosine similarity, and returns the top-K most similar past interactions.
 * This runs entirely on Dispatchers.IO — no UI thread impact.
 */
@Entity(tableName = "interaction_records")
data class InteractionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Lightweight image embedding as serialized FloatArray (256 floats). */
    @ColumnInfo(name = "image_embedding", typeAffinity = ColumnInfo.BLOB)
    val imageEmbedding: ByteArray,

    /** The raw prompt sent to the VLM for this interaction. */
    val prompt: String,

    /** The VLM-generated output text. */
    val output: String,

    /** User feedback — edited text, preference tags, or empty string. */
    val feedback: String = "",

    /** Timestamp when this interaction occurred (System.currentTimeMillis). */
    val timestamp: Long = System.currentTimeMillis(),

    /** Optional tags for categorical filtering (e.g., "navigation", "text_reading"). */
    val tags: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InteractionRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** Number of floats in the embedding vector (16×16 grayscale). */
        const val EMBEDDING_SIZE = 256

        /** Serialize a FloatArray to ByteArray for Room storage. */
        fun serializeEmbedding(floatArray: FloatArray): ByteArray {
            require(floatArray.size == EMBEDDING_SIZE) {
                "Embedding must be $EMBEDDING_SIZE floats, got ${floatArray.size}"
            }
            val byteBuffer = java.nio.ByteBuffer.allocate(floatArray.size * 4)
            byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            floatArray.forEach { byteBuffer.putFloat(it) }
            return byteBuffer.array()
        }

        /** Deserialize a ByteArray back to FloatArray. */
        fun deserializeEmbedding(bytes: ByteArray): FloatArray {
            val byteBuffer = java.nio.ByteBuffer.wrap(bytes)
            byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val floatArray = FloatArray(bytes.size / 4)
            byteBuffer.asFloatBuffer().get(floatArray)
            return floatArray
        }
    }
}
