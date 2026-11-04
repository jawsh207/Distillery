// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * The weights of a perceptron model (the tagger, the word guesser), in the
 * binary form tools/perceptron_pack.py writes ("DPM2"): feature names are
 * 32-bit hashes in a sorted array, so loading is a few array reads rather
 * than parsing hundreds of thousands of lines. Weights are 16-bit; only their
 * order matters.
 */
class PackedPerceptron private constructor(
    val classes: Array<String>,
    val tagdict: Map<String, Int>,
    private val hashes: IntArray,
    private val offsets: IntArray,
    private val pairClasses: ShortArray,
    private val pairWeights: ShortArray,
) {

    /** Adds [feature]'s weights to [scores] (indexed by class). */
    fun add(feature: String, scores: IntArray) {
        val k = hashes.binarySearch(hash32(feature))
        if (k < 0) return
        for (p in offsets[k] until offsets[k + 1]) scores[pairClasses[p].toInt()] += pairWeights[p].toInt()
    }

    /** The highest-scoring class over [features]; ties go to the lowest index. */
    fun best(features: Array<String>, scores: IntArray): Int {
        scores.fill(0)
        for (f in features) add(f, scores)
        var best = 0
        for (c in 1 until scores.size) if (scores[c] > scores[best]) best = c
        return best
    }

    companion object {
        fun fnv1a64(text: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (b in text.encodeToByteArray()) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return h
        }

        /** fnv1a64 with its two halves xored, as tools/perceptron_pack.py `hash32`. */
        fun hash32(text: String): Int {
            val h = fnv1a64(text)
            return (h xor (h ushr 32)).toInt()
        }

        fun load(gzipped: InputStream): PackedPerceptron {
            val input = DataInputStream(GZIPInputStream(gzipped, 1 shl 16).buffered(1 shl 16))
            val magic = ByteArray(4).also { input.readFully(it) }
            require(magic.decodeToString() == "DPM2") { "Not a packed perceptron model" }
            fun utf(): String = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }.decodeToString()

            val classes = Array(input.readInt()) { utf() }
            val tagdict = HashMap<String, Int>()
            repeat(input.readInt()) { tagdict[utf()] = input.readShort().toInt() }

            val n = input.readInt()
            val hashes = IntArray(n)
            ByteBuffer.wrap(ByteArray(n * 4).also { input.readFully(it) }).asIntBuffer().get(hashes)
            val counts = ShortArray(n)
            ByteBuffer.wrap(ByteArray(n * 2).also { input.readFully(it) }).asShortBuffer().get(counts)
            val offsets = IntArray(n + 1)
            for (k in 0 until n) offsets[k + 1] = offsets[k] + (counts[k].toInt() and 0xffff)
            val pairs = input.readInt()
            val pairClasses = ShortArray(pairs)
            ByteBuffer.wrap(ByteArray(pairs * 2).also { input.readFully(it) }).asShortBuffer().get(pairClasses)
            val pairWeights = ShortArray(pairs)
            ByteBuffer.wrap(ByteArray(pairs * 2).also { input.readFully(it) }).asShortBuffer().get(pairWeights)
            return PackedPerceptron(classes, tagdict, hashes, offsets, pairClasses, pairWeights)
        }
    }
}
