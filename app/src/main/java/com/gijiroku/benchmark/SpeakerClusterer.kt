package com.gijiroku.benchmark

import kotlin.math.sqrt

/** Result of clustering the valid speaker embeddings in their original time order. */
data class SpeakerClusteringResult(
    val clusterIds: IntArray,
    val speakerCount: Int,
    val isSpeakerCountSpecified: Boolean
)

/**
 * Average-linkage agglomerative clustering for the speaker embeddings produced for each audio
 * window. When the number of speakers is known, it is the most reliable input and is used as an
 * explicit target. Otherwise, candidates from one to four speakers are compared.
 */
object SpeakerClusterer {

    private const val AUTO_MAX_SPEAKERS = 4
    // A higher candidate must improve its separation by this much; close candidates keep the
    // smaller count so one speaker is not split into several labels too easily.
    private const val AUTO_SCORE_MIN_IMPROVEMENT = 0.05f
    private const val SINGLETON_CLUSTER_SCORE = -0.25f

    /**
     * Returns an id for every input embedding (-1 for null entries), plus the effective number of
     * clusters. A requested count is constrained only by the number of valid embeddings; automatic
     * detection is intentionally limited to the app's 2--4 person meeting target.
     */
    fun cluster(embeddings: List<FloatArray?>, numSpeakers: Int? = null): SpeakerClusteringResult {
        val validIndices = embeddings.indices.filter { embeddings[it] != null }
        if (validIndices.isEmpty()) {
            return SpeakerClusteringResult(
                clusterIds = IntArray(embeddings.size) { -1 },
                speakerCount = 0,
                isSpeakerCountSpecified = numSpeakers != null
            )
        }

        val normalized = validIndices.map { normalize(embeddings[it]!!) }
        val targetCount = numSpeakers?.coerceAtLeast(1)?.coerceAtMost(normalized.size)
        val selected = if (targetCount != null) {
            buildClusters(normalized, targetCount)
        } else {
            selectAutomaticClusters(normalized)
        }

        return SpeakerClusteringResult(
            clusterIds = labelsFor(embeddings.size, validIndices, selected),
            speakerCount = selected.size,
            isSpeakerCountSpecified = targetCount != null
        )
    }

    /** コサイン類似度（-1..1）。 */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float = dot(normalize(a), normalize(b))

    fun labelName(clusterId: Int): String {
        if (clusterId < 0) return "話者未分離"
        val letter = 'A' + (clusterId % 26)
        val suffix = if (clusterId >= 26) (clusterId / 26).toString() else ""
        return "話者$letter$suffix"
    }

    private fun selectAutomaticClusters(vectors: List<FloatArray>): List<MutableList<Int>> {
        var best = buildClusters(vectors, targetCount = 1)
        var bestScore = 0f
        val maxCount = minOf(AUTO_MAX_SPEAKERS, vectors.size)
        for (candidateCount in 2..maxCount) {
            val candidate = buildClusters(vectors, candidateCount)
            val score = separationScore(candidate, vectors)
            if (score > bestScore + AUTO_SCORE_MIN_IMPROVEMENT) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /** Builds a fresh hierarchy cut, retaining temporal first-appearance ordering of clusters. */
    private fun buildClusters(
        vectors: List<FloatArray>,
        targetCount: Int
    ): List<MutableList<Int>> {
        val clusters = vectors.indices.map { mutableListOf(it) }.toMutableList()
        while (clusters.size > targetCount) {
            var bestI = 0
            var bestJ = 1
            var bestSimilarity = -Float.MAX_VALUE
            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val similarity = averageLinkageSimilarity(clusters[i], clusters[j], vectors)
                    if (similarity > bestSimilarity) {
                        bestSimilarity = similarity
                        bestI = i
                        bestJ = j
                    }
                }
            }
            clusters[bestI].addAll(clusters[bestJ])
            clusters.removeAt(bestJ)
        }
        return clusters
    }

    /**
     * Mean silhouette-like separation score using cosine similarity. Single-member clusters are
     * penalised because a short, noisy window should not by itself create a new speaker label.
     */
    private fun separationScore(clusters: List<List<Int>>, vectors: List<FloatArray>): Float {
        if (clusters.size <= 1) return 0f
        var total = 0f
        var count = 0
        for (clusterIndex in clusters.indices) {
            val ownCluster = clusters[clusterIndex]
            for (member in ownCluster) {
                if (ownCluster.size == 1) {
                    total += SINGLETON_CLUSTER_SCORE
                    count++
                    continue
                }
                val cohesion = ownCluster.filter { it != member }
                    .map { dot(vectors[member], vectors[it]) }
                    .average()
                    .toFloat()
                val nearestOther = clusters.indices
                    .filter { it != clusterIndex }
                    .maxOf { other ->
                        clusters[other].map { dot(vectors[member], vectors[it]) }.average().toFloat()
                    }
                total += cohesion - nearestOther
                count++
            }
        }
        return if (count == 0) 0f else total / count
    }

    private fun labelsFor(
        inputSize: Int,
        validIndices: List<Int>,
        clusters: List<List<Int>>
    ): IntArray {
        val labels = IntArray(inputSize) { -1 }
        clusters.forEachIndexed { clusterId, members ->
            for (member in members) labels[validIndices[member]] = clusterId
        }
        return labels
    }

    private fun averageLinkageSimilarity(a: List<Int>, b: List<Int>, vectors: List<FloatArray>): Float {
        var sum = 0f
        for (i in a) for (j in b) sum += dot(vectors[i], vectors[j])
        return sum / (a.size * b.size)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm < 1e-6f) return v
        return FloatArray(v.size) { v[it] / norm }
    }
}
