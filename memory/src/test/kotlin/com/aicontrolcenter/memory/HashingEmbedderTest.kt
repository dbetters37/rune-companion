package com.aicontrolcenter.memory

import com.aicontrolcenter.core.registry.Locality
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HashingEmbedderTest {
    private val embedder = HashingEmbedder()

    private fun cos(a: String, b: String) = VectorMath.cosine(embedder.vectorOf(a), embedder.vectorOf(b))

    @Test
    fun `identity is offline and sized as named`() {
        assertEquals("hash-v1-384", embedder.id)
        assertEquals(384, embedder.dimensions)
        assertEquals(Locality.ON_DEVICE, embedder.locality)
        assertEquals(384, embedder.vectorOf("anything").size)
    }

    @Test
    fun `vectors are deterministic across instances and calls`() = runTest {
        val text = "The owner prefers green tea in the morning"
        val a = HashingEmbedder().embed(listOf(text, "second")).first()
        val b = HashingEmbedder().vectorOf(text)
        assertContentEquals(a, b)
        assertContentEquals(b, embedder.vectorOf(text))
    }

    @Test
    fun `the hash and the vector never change for an existing id`() {
        // Stored vectors depend on these exact values: a change here needs a new embedder id.
        assertEquals(-5723852236366071090L, StableHash.hash64("u:tea"))
        val v = embedder.vectorOf("The owner prefers green tea")
        assertEquals(GOLDEN, v.indices.filter { v[it] != 0f }.take(6).map { it to "%.5f".format(java.util.Locale.ROOT, v[it]) })
    }

    @Test
    fun `vectors are unit length and empty text is the zero vector`() = runTest {
        for (text in listOf("tea", "A much longer sentence about planning the quarterly budget review", "Ünïcödé façade")) {
            val v = embedder.vectorOf(text)
            assertTrue(abs(sqrt(v.sumOf { it.toDouble() * it }) - 1.0) < 1e-5, "not unit length for '$text'")
        }
        val empty = embedder.embed(listOf("", "   ", "the and of")).map { v -> v.all { it == 0f } }
        assertEquals(listOf(true, true, true), empty)
    }

    @Test
    fun `paraphrases and inflections score clearly higher than unrelated text`() {
        val base = "The owner prefers green tea in the morning"
        val paraphrase = cos(base, "In the mornings the owner likes drinking green tea")
        val inflected = cos("She is planning the trips", "She planned a trip")
        val unrelatedA = cos(base, "The car needs new winter tyres before December")
        val unrelatedB = cos("She is planning the trips", "Quarterly revenue grew by four percent")
        assertTrue(paraphrase > 0.35, "paraphrase $paraphrase")
        assertTrue(inflected > 0.35, "inflection $inflected")
        assertTrue(abs(unrelatedA) < 0.12 && abs(unrelatedB) < 0.12, "unrelated $unrelatedA $unrelatedB")
        assertTrue(paraphrase > 3 * abs(unrelatedA) && inflected > 3 * abs(unrelatedB))
    }

    @Test
    fun `typos still land near the intended word`() {
        val typo = cos("restaurant reservation", "resturant resevation")
        val unrelated = cos("restaurant reservation", "weather forecast tomorrow")
        assertTrue(typo > 0.3, "typo $typo")
        assertTrue(unrelated < 0.15, "unrelated $unrelated")
    }

    @Test
    fun `accents and case are folded and other scripts are embedded`() {
        assertContentEquals(embedder.vectorOf("Café CRÈME"), embedder.vectorOf("cafe creme"))
        assertTrue(embedder.vectorOf("Привет мир").any { it != 0f })
        assertTrue(cos("会议记录", "会议记录 明天") > 0.5)
    }

    @Test
    fun `the analyzer folds inflections onto one stem and drops stop words`() {
        val groups = listOf(
            listOf("prefers", "preferred", "preferring", "prefer"),
            listOf("plans", "planned", "planning", "plan"),
            listOf("making", "makes", "make"),
            listOf("berries", "berry"),
            listOf("classes", "class"),
        )
        for (group in groups) assertEquals(1, group.map(TextAnalyzer::stem).toSet().size, "stems of $group")
        assertEquals(listOf("owner", "drink", "tea"), TextAnalyzer.terms("The owner is drinking the tea"))
        assertEquals(listOf("don", "t", "x2", "cafe"), TextAnalyzer.words("Don't x2 — café!"))
    }

    private companion object {
        val GOLDEN: List<Pair<Int, String>> = listOf(
            8 to "-0.11149", 35 to "-0.11149", 65 to "-0.11149", 99 to "0.11149", 116 to "-0.11149", 118 to "0.11149",
        )
    }
}
