package com.jarvis.shared

import com.jarvis.shared.brain.BrainUnavailableException
import com.jarvis.shared.brain.BrainContext
import com.jarvis.shared.brain.BrainRequest
import com.jarvis.shared.brain.ClaudeBrain
import com.jarvis.shared.brain.Depth
import com.jarvis.shared.brain.Occasion
import com.jarvis.shared.brain.Prompts
import com.jarvis.shared.model.DayRecord
import com.jarvis.shared.model.JarvisSettings
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudeBrainTest {
    private val request = BrainRequest(
        userText = "explain recursion",
        history = emptyList(),
        context = BrainContext(LocalDateTime(2026, 10, 21, 23, 55), DayRecord(LocalDate(2026, 10, 21)), emptyList(), JarvisSettings()),
        depth = Depth.DEEP,
        occasion = Occasion.NIGHT_DEBRIEF,
    )

    @Test fun missingKeyIsReportedNotCrashed() = runTest {
        val e = assertFailsWith<BrainUnavailableException> { ClaudeBrain(apiKey = { null }).reply(request) }
        assertTrue("no Claude API key" in e.message.orEmpty())
    }

    @Test fun userMessageCarriesContext() {
        val msg = Prompts.buildUserMessage(request)
        assertTrue("Occasion: NIGHT_DEBRIEF" in msg)
        assertTrue("<user_said>explain recursion</user_said>" in msg)
        assertTrue("Today's plan: none set" in msg)
    }
}
