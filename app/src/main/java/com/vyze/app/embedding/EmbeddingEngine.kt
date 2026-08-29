package com.vyze.app.embedding

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.vyze.app.data.InteractionRecord

/**
 * Lightweight on-device image embedding engine for adaptive intelligence.
 *
 * Generates fixed-length (256-float) embeddings from camera frames by:
 * 1. Downscaling to 16×16 grayscale
 * 2. Normalizing pixel values to 0.0–1.0
 * 3. Returning the flattened pixel vector as the embedding
 *
 * ## Design Rationale
 * This approach is intentionally simple and fast (~1ms per frame) because:
 * - No external ML model required (zero additional APK size)
 * - Runs entirely on CPU with no GPU delegation needed
 * - Captures basic visual similarity (color distribution, brightness, layout)
 * - Sufficient for retrieving contextually similar past interactions
 *
 * ## Limitations
 * - Cannot capture semantic similarity (e.g., "chair" vs "stool")
 * - Works best for retrieving visually similar scenes, not conceptually similar ones
 * - For semantic similarity, a dedicated embedding model (e.g., MediaPipe Image Embedder)
 *   would be needed — this is a lightweight fallback
 *
 * ## Usage
 * ```kotlin
 * val embedding = EmbeddingEngine.generateEmbedding(bitmap)
 * val serialized = InteractionRecord.serializeEmbedding(embedding)
 * ```
 */
object EmbeddingEngine {

    private const val TAG = "EmbeddingEngine"
    private const val GRID_SIZE = 16  // 16×16 = 256 floats

    /**
     * Generate a lightweight image embedding from a Bitmap.
     *
     * @param bitmap  Input image (any size — will be downscaled to 16×16)
     * @return FloatArray of 256 values (0.0–1.0), normalized grayscale pixel intensities
     */
    fun generateEmbedding(bitmap: Bitmap): FloatArray {
        // 1. Downscale to 16×16
        val scaled = Bitmap.createScaledBitmap(bitmap, GRID_SIZE, GRID_SIZE, true)

        // 2. Extract grayscale pixel values and normalize to 0.0–1.0
        val embedding = FloatArray(GRID_SIZE * GRID_SIZE)
        for (y in 0 until GRID_SIZE) {
            for (x in 0 until GRID_SIZE) {
                val pixel = scaled.getPixel(x, y)
                // ITU-R BT.601 luma formula: 0.299R + 0.587G + 0.114B
                val luma = (0.299f * Color.red(pixel) +
                    0.587f * Color.green(pixel) +
                    0.114f * Color.blue(pixel)) / 255.0f
                embedding[y * GRID_SIZE + x] = luma
            }
        }

        // 3. Recycle the scaled bitmap if it's a different instance
        if (scaled !== bitmap) {
            try { scaled.recycle() } catch (_: Throwable) {}
        }

        return embedding
    }

    /**
     * Compute cosine similarity between two embedding vectors.
     *
     * @return Similarity score in range [-1.0, 1.0]. Higher = more similar.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding size mismatch: ${a.size} vs ${b.size}" }

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            dotProduct += a[i] * b[i].toDouble()
            normA += a[i] * a[i].toDouble()
            normB += b[i] * b[i].toDouble()
        }

        val denominator = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denominator == 0.0) 0.0f else (dotProduct / denominator).toFloat()
    }

    /**
     * Find the top-K most similar interactions from a list of candidates.
     *
     * @param queryEmbedding  The embedding to compare against
     * @param candidates      Past interaction records with embeddings
     * @param topK            Number of results to return
     * @param minSimilarity   Minimum similarity threshold (discards low-quality matches)
     * @return List of (InteractionRecord, similarityScore) pairs, most similar first
     */
    fun findTopK(
        queryEmbedding: FloatArray,
        candidates: List<com.vyze.app.data.InteractionRecord>,
        topK: Int = 5,
        minSimilarity: Float = 0.3f
    ): List<Pair<com.vyze.app.data.InteractionRecord, Float>> {
        return candidates
            .mapNotNull { record ->
                try {
                    val recordEmbedding = InteractionRecord.deserializeEmbedding(record.imageEmbedding)
                    val similarity = cosineSimilarity(queryEmbedding, recordEmbedding)
                    if (similarity >= minSimilarity) record to similarity else null
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to deserialize embedding for record ${record.id}: ${e.message}")
                    null
                }
            }
            .sortedByDescending { it.second }
            .take(topK)
    }
}
