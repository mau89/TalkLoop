package com.mau89.talkloop

import com.mau89.talkloop.llm.OllamaTuning
import kotlin.test.*

class OllamaTuningFormTest {
    @Test
    fun editableProfileSurvivesStorageRoundTrip() {
        val edited = OllamaTuningForm.from(OllamaTuning()).copy(temperature = "0,3",
            presencePenalty = "-0.5", context = "8192", answer = "900", memory = "700",
            verdict = "250", thinking = true, recipePrompts = false).parse()
        assertNotNull(edited)
        assertEquals(0.3, edited.temperature)
        assertEquals(edited, decodeOllamaTuning(encodeOllamaTuning(edited)))
    }

    @Test
    fun invalidInputsCannotBeApplied() {
        val form = OllamaTuningForm.from(OllamaTuning())
        listOf(form.copy(temperature = "NaN"), form.copy(temperature = "2.1"),
            form.copy(presencePenalty = "-3"), form.copy(context = ""),
            form.copy(answer = "0"), form.copy(memory = "24576"),
            form.copy(verdict = "1.5"), form.copy(context = "2147483648"))
            .forEach { assertNull(it.parse()) }
    }

    @Test
    fun oldOrBrokenPreferencesLoadDay29Defaults() {
        assertEquals(OllamaTuning(), decodeOllamaTuning(null))
        assertEquals(OllamaTuning(), decodeOllamaTuning("broken"))
        assertEquals(OllamaTuning(), decodeOllamaTuning("{\"temperature\":-1}"))
    }

    @Test
    fun presetsCanBeAppliedWithoutChangingValues() {
        for (profile in listOf(OllamaTuningForm.baseline, OllamaTuning())) {
            assertEquals(profile, OllamaTuningForm.from(profile).parse())
        }
    }
}
