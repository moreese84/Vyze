package com.vyze.app.memory

import android.graphics.Bitmap
import android.util.Log
import com.vyze.app.data.InteractionDao
import com.vyze.app.data.InteractionRecord
import com.vyze.app.embedding.EmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for Vyze's adaptive intelligence memory system.
 *
 * Orchestrates:
 * 1. **Storing** interactions — generates image embedding, serializes, persists to Room
 * 2. **Retrieving** similar past interactions — cosine similarity search over stored embeddings
 * 3. **Pruning** — keeps storage bounded by discarding oldest records
 *
 * ## Thread Safety
 * All operations run on [Dispatchers.IO]. The repository holds no mutable state
 * beyond the DAO reference, so it is safe to use from any coroutine scope.
 *
 * ## Performance
 * - Embedding generation: ~1ms (16×16 grayscale, pure CPU)
 * - Similarity search: <10ms for 500 records (linear scan, in-memory)
 * - Room writes: <5ms (single INSERT on indexed table)
 * - All off main thread — zero UI impact
 *
 * ## Usage
 * ```kotlin
 * val repo = MemoryRepository(interactionDao)
 *
 * // After inference:
 * repo.storeInteraction(bitmap, prompt, output)
 *
 * // Before next inference:
 * val similar = repo.findSimilar(bitmap, topK = 3)
 * ```
 */
class MemoryRepository(private val interactionDao: InteractionDao) {

    private val tag = "MemoryRepository"

    /**
     * Store a completed interaction with its image embedding.
     *
     * Generates a 256-float perceptual embedding from the camera frame,
     * serializes it, and persists the full interaction record to Room.
     *
     * Automatically prunes excess records (keeps most recent 1000).
     *
     * @param bitmap   The camera frame used for this interaction
     * @param prompt   The prompt sent to the VLM
     * @param output   The VLM-generated response
     * @param feedback Optional user feedback (edited text, preference tags)
     * @param tags     Optional categorical tags (e.g., "navigation", "text_reading")
     */
    suspend fun storeInteraction(
        bitmap: Bitmap,
        prompt: String,
        output: String,
        feedback: String = "",
        tags: String = ""
    ) = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()

            // 1. Generate lightweight embedding from the camera frame
            val embedding = EmbeddingEngine.generateEmbedding(bitmap)
            val serializedEmbedding = InteractionRecord.serializeEmbedding(embedding)

            // 2. Persist to Room
            val record = InteractionRecord(
                imageEmbedding = serializedEmbedding,
                prompt = prompt,
                output = output,
                feedback = feedback,
                tags = tags
            )
            interactionDao.insert(record)

            // 3. Prune excess records (keep most recent 1000)
            val count = interactionDao.getCount()
            if (count > MAX_INTERACTIONS) {
                interactionDao.pruneExcess(MAX_INTERACTIONS)
                Log.d(tag, "Pruned interaction records: $count → $MAX_INTERACTIONS")
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(tag, "Stored interaction #${record.id} in ${elapsed}ms " +
                "(embedding: ${embedding.size} floats)")

        } catch (e: Throwable) {
            Log.e(tag, "Failed to store interaction: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Find the top-K most visually similar past interactions.
     *
     * Loads all stored embeddings into memory, computes cosine similarity
     * against the query bitmap's embedding, and returns matches above the
     * similarity threshold.
     *
     * This runs entirely on Dispatchers.IO — no UI thread impact.
     *
     * @param bitmap   The current camera frame to compare against
     * @param topK     Maximum number of similar interactions to return (default: 5)
     * @param minSim   Minimum similarity score to include (default: 0.3)
     * @return List of similar interactions with scores, most similar first
     */
    suspend fun findSimilar(
        bitmap: Bitmap,
        topK: Int = 5,
        minSim: Float = 0.3f
    ): List<SimilarInteraction> = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()

            // 1. Generate query embedding
            val queryEmbedding = EmbeddingEngine.generateEmbedding(bitmap)

            // 2. Load all stored interactions (bounded by MAX_INTERACTIONS)
            val candidates = interactionDao.getRecent(MAX_INTERACTIONS)
            if (candidates.isEmpty()) {
                Log.d(tag, "No stored interactions for similarity search")
                return@withContext emptyList()
            }

            // 3. Find top-K matches via cosine similarity
            val matches = EmbeddingEngine.findTopK(
                queryEmbedding = queryEmbedding,
                candidates = candidates,
                topK = topK,
                minSimilarity = minSim
            )

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(tag, "Similarity search: ${candidates.size} candidates → " +
                "${matches.size} matches in ${elapsed}ms")

            matches.map { (record, score) ->
                SimilarInteraction(
                    record = record,
                    similarityScore = score
                )
            }

        } catch (e: Throwable) {
            Log.e(tag, "Similarity search failed: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get recent interactions for context (non-similarity-based).
     * Useful for providing spatial continuity even without visual matching.
     */
    suspend fun getRecentInteractions(limit: Int = 5): List<InteractionRecord> =
        withContext(Dispatchers.IO) {
            try {
                interactionDao.getRecent(limit)
            } catch (e: Throwable) {
                Log.e(tag, "Failed to get recent interactions: ${e.message}")
                emptyList()
            }
        }

    /**
     * Get the total number of stored interactions.
     */
    suspend fun getInteractionCount(): Int = withContext(Dispatchers.IO) {
        try {
            interactionDao.getCount()
        } catch (e: Throwable) {
            Log.e(tag, "Failed to get interaction count: ${e.message}")
            0
        }
    }

    companion object {
        /** Maximum interaction records to keep in the database. */
        private const val MAX_INTERACTIONS = 1000
    }
}

/**
 * A past interaction with its similarity score relative to the current frame.
 *
 * @param record           The stored interaction record
 * @param similarityScore  Cosine similarity score (0.0–1.0, higher = more similar)
 */
data class SimilarInteraction(
    val record: InteractionRecord,
    val similarityScore: Float
)
