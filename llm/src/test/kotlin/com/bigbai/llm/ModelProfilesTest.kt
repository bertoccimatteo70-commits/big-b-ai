package com.bigbai.llm

import com.bigbai.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfilesTest {

    @Test
    fun `coder models get a low temperature and a large budget`() {
        val profile = ModelProfiles.detect("qwen2.5-coder-1.5b-instruct-q4_k_m")
        assertEquals("coder", profile.family)
        assertTrue(profile.codeFriendly)
        assertTrue(profile.temperature <= 0.3f)
        assertTrue(profile.maxTokens >= 2048)
    }

    @Test
    fun `tiny models are concise and declared limited`() {
        val profile = ModelProfiles.detect("tinyllama-1.1b-chat-q4_k_m")
        assertTrue(profile.concise)
        assertTrue(profile.limitedReasoning)
        assertTrue(profile.maxTokens in 512..768)
    }

    @Test
    fun `qwen and llama do not share the same sampling`() {
        val qwen = ModelProfiles.detect("qwen2.5-3b-instruct-q4_k_m")
        val llama = ModelProfiles.detect("llama-3.2-3b-instruct-q4_k_m")
        assertFalse(qwen.family == llama.family)
        assertFalse(qwen.topP == llama.topP && qwen.temperature == llama.temperature)
    }

    @Test
    fun `manual settings only win when advanced is unlocked`() {
        val profile = ModelProfiles.detect("qwen2.5-7b-instruct-q4_k_m")
        val base = AppSettings()
        assertEquals(profile.temperature, profile.params(base).temperature, 0.001f)

        val advanced = base.copy(
            advancedUnlocked = true,
            temperature = 1.1f,
            topP = 0.5f,
            topK = 10,
            maxTokens = 2048,
        )
        val params = profile.params(advanced)
        assertEquals(1.1f, params.temperature, 0.001f)
        assertEquals(0.5f, params.topP, 0.001f)
        assertEquals(10, params.topK)
        assertEquals(2048, params.maxTokens)
    }

    @Test
    fun `small models always keep a bounded answer budget`() {
        val profile = ModelProfiles.detect("qwen2.5-0.5b-instruct-q4_k_m")
        val params = profile.params(AppSettings(maxTokens = 4096))
        assertTrue(params.maxTokens in 512..768)
    }

    @Test
    fun `small models get stronger repetition control and min-p`() {
        val tiny = ModelProfiles.detect("qwen2.5-0.5b-instruct")
        val large = ModelProfiles.detect("qwen2.5-7b-instruct")
        assertTrue(tiny.repeatPenalty > large.repeatPenalty)
        assertTrue(tiny.minP > 0f)

        val advanced = AppSettings(advancedUnlocked = true, repeatPenalty = 1.4f, minP = 0.1f)
        val params = tiny.params(advanced)
        assertEquals(1.4f, params.repeatPenalty, 0.001f)
        assertEquals(0.1f, params.minP, 0.001f)
    }

    @Test
    fun `style hint is present for concise models only`() {
        assertTrue(ModelProfiles.detect("smollm-360m").styleHint(true).isNotBlank())
        assertEquals("", ModelProfiles.detect("qwen2.5-7b").styleHint(true))
    }
}
