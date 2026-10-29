// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * The weights of a perceptron model (the tagger, the word guesser), in the
 * binary form tools/perceptron_pack.py writes: feature names are 64-bit
 * FNV-1a hashes in a sorted array, so loading is a few array reads rather
 * than parsing hundreds of thousands of lines.
 */
class PackedPerceptron private constructor(
    val classes: Array<String>,
    val tagdict: Map<String, Int>,
    private val hashes: LongArray,
    private val offsets: IntArray,
    private val pairClasses: ShortArray,
    private val pairWeights: IntArray,
) {

    /** Adds [feature]'s weights to [scores] (indexed by class). */
    fun add(feature: String, scores: IntArray) {
        val k = hashes.binarySearch(fnv1a64(feature))
        if (k < 0) return
        for (p in offsets[k] until offsets[k + 1]) scores[pairClasses[p].toInt()] += pairWeights[p]
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

        fun load(gzipped: InputStream): PackedPerceptron {
            val input = DataInputStream(GZIPInputStream(gzipped, 1 shl 16).buffered(1 shl 16))
            val magic = ByteArray(4).also { input.readFully(it) }
            require(magic.decodeToString() == "DPM1") { "Not a packed perceptron model" }
            fun utf(): String = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }.decodeToString()

            val classes = Array(input.readInt()) { utf() }
            val tagdict = HashMap<String, Int>()
            repeat(input.readInt()) { tagdict[utf()] = input.readShort().toInt() }

            val n = input.readInt()
            val hashes = LongArray(n)
            ByteBuffer.wrap(ByteArray(n * 8).also { input.readFully(it) }).asLongBuffer().get(hashes)
            val offsets = IntArray(n + 1)
            ByteBuffer.wrap(ByteArray((n + 1) * 4).also { input.readFully(it) }).asIntBuffer().get(offsets)
            val pairs = input.readInt()
            val pairClasses = ShortArray(pairs)
            ByteBuffer.wrap(ByteArray(pairs * 2).also { input.readFully(it) }).asShortBuffer().get(pairClasses)
            val pairWeights = IntArray(pairs)
            ByteBuffer.wrap(ByteArray(pairs * 4).also { input.readFully(it) }).asIntBuffer().get(pairWeights)
            return PackedPerceptron(classes, tagdict, hashes, offsets, pairClasses, pairWeights)
        }
    }
}
