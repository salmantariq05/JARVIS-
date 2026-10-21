package com.jarvis.shared

import com.jarvis.shared.brain.Brain
import com.jarvis.shared.brain.BrainKind
import com.jarvis.shared.brain.BrainReply
import com.jarvis.shared.brain.BrainRequest
import com.jarvis.shared.brain.BrainRouter
import com.jarvis.shared.brain.BrainUnavailableException
import com.jarvis.shared.brain.DebriefComposer
import com.jarvis.shared.brain.Occasion
import com.jarvis.shared.brain.OfflineBrain
import com.jarvis.shared.data.InMemoryTextStorage
import com.jarvis.shared.data.JarvisRepository
import com.jarvis.shared.model.BrainMode
import com.jarvis.shared.model.DayRecord
import com.jarvis.shared.model.LogCategory
import com.jarvis.shared.model.LogEntry
import com.jarvis.shared.model.PlanItem
import com.jarvis.shared.model.PlanStatus
import com.jarvis.shared.model.ScheduledCall
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeScheduler : CallScheduler {
    val scheduled = mutableMapOf<Int, ScheduledCall>()
    override fun schedule(call: ScheduledCall) {
        scheduled[call.id] = call
    }
    override fun cancel(callId: Int) {
        scheduled.remove(callId)
    }
}

class JarvisAssistantTest {
    private var now = LocalDateTime(2026, 10, 21, 14, 0)
    private val repo = JarvisRepository(InMemoryTextStorage())
    private val scheduler = FakeScheduler()

    private fun assistant(claude: Brain? = null, hasKey: Boolean = false, online: Boolean = true) = JarvisAssistant(
        repo,
        BrainRouter(claude, OfflineBrain(), hasClaudeKey = { hasKey }, isOnline = { online }),
        scheduler,
    ) { now }

    @Test fun offlineDayFlow() = runTest {
        val jarvis = assistant()
        val chat = jarvis.newSession(Occasion.CHAT)

        jarvis.respond(chat, "my plan for today is DSA, OS assignment and gym")
        jarvis.respond(chat, "I studied DSA for 90 minutes")
        jarvis.respond(chat, "I finished the OS assignment")
        val call = jarvis.respond(chat, "call me at 7 am")

        assertEquals(BrainKind.OFFLINE, call.brain)
        val day = repo.day(LocalDate(2026, 10, 21))
        assertEquals(3, day.plan.size)
        assertEquals(PlanStatus.DONE, day.plan[1].status)
        assertEquals(90, day.studyMinutes)
        val alarm = scheduler.scheduled.values.single { it.id >= JarvisRepository.FIRST_ONE_OFF_CALL_ID }
        assertEquals(LocalDateTime(2026, 10, 22, 7, 0), alarm.at)

        now = LocalDateTime(2026, 10, 21, 23, 55)
        val debrief = jarvis.open(jarvis.newSession(Occasion.NIGHT_DEBRIEF))
        assertTrue("completed 1" in debrief.speech, debrief.speech)
        assertTrue("DSA for 1 hour 30 minutes" in debrief.speech, debrief.speech)
        assertEquals(debrief.speech, repo.day(LocalDate(2026, 10, 21)).debrief)
    }

    @Test fun missingTimeFollowUp() = runTest {
        val jarvis = assistant()
        val chat = jarvis.newSession(Occasion.CHAT)
        val ask = jarvis.respond(chat, "remind me to call mom")
        assertTrue("At what time" in ask.speech)
        jarvis.respond(chat, "at 6 pm")
        val alarm = repo.calls().single()
        assertEquals(LocalDateTime(2026, 10, 21, 18, 0), alarm.at)
        assertEquals("call mom", alarm.label)
    }

    @Test fun morningCallTurnsAnythingIntoAPlan() = runTest {
        val jarvis = assistant()
        val session = jarvis.newSession(Occasion.MORNING_PLAN)
        jarvis.open(session)
        jarvis.respond(session, "DSA revision, physics lab report and gym")
        assertEquals(3, repo.day(LocalDate(2026, 10, 21)).plan.size)
    }

    @Test fun fallsBackWhenClaudeFails() = runTest {
        val broken = object : Brain {
            override suspend fun reply(request: BrainRequest): BrainReply =
                throw BrainUnavailableException("your Claude credit has run out")
        }
        val jarvis = assistant(claude = broken, hasKey = true)
        val chat = jarvis.newSession(Occasion.CHAT)
        val first = jarvis.respond(chat, "what time is it")
        assertEquals(BrainKind.OFFLINE, first.brain)
        assertTrue("credit has run out" in first.speech)
        val second = jarvis.respond(chat, "what time is it")
        assertTrue("credit" !in second.speech, "fallback is announced once per conversation")
    }

    @Test fun claudeActionsAreExecuted() = runTest {
        val fake = object : Brain {
            override suspend fun reply(request: BrainRequest) = com.jarvis.shared.data.JarvisJson.decodeFromString(
                BrainReply.serializer(),
                """{"speech":"Done.","actions":[{"type":"SET_DAILY_CALL","label":"night_debrief","time":"23:30"},
                   {"type":"LOG","category":"STUDY","text":"Graph BFS","subject":"DSA","minutes":45}],"end_conversation":false}""",
            )
        }
        val jarvis = assistant(claude = fake, hasKey = true)
        val turn = jarvis.respond(jarvis.newSession(Occasion.CHAT), "night call saade gyarah baje kar do, aur maine BFS parha")
        assertEquals(BrainKind.CLAUDE_QUICK, turn.brain)
        assertEquals(LocalTime(23, 30), repo.settings().nightDebriefTime)
        assertEquals(LocalDateTime(2026, 10, 21, 23, 30), scheduler.scheduled[CallIds.NIGHT]?.at)
        assertEquals(45, repo.day(LocalDate(2026, 10, 21)).studyMinutes)
    }

    @Test fun offlineModeNeverCallsClaude() = runTest {
        repo.updateSettings { it.copy(brainMode = BrainMode.OFFLINE) }
        val explode = object : Brain {
            override suspend fun reply(request: BrainRequest): BrainReply = error("should not be called")
        }
        val turn = assistant(claude = explode, hasKey = true).let { it.respond(it.newSession(Occasion.CHAT), "hello") }
        assertEquals(BrainKind.OFFLINE, turn.brain)
    }

    @Test fun rescheduleDropsPastCalls() = runTest {
        val jarvis = assistant()
        jarvis.respond(jarvis.newSession(Occasion.CHAT), "call me in 10 minutes")
        now = LocalDateTime(2026, 10, 21, 15, 0)
        scheduler.scheduled.clear()
        jarvis.rescheduleAll()
        assertTrue(repo.calls().isEmpty())
        assertEquals(setOf(CallIds.MORNING, CallIds.NIGHT), scheduler.scheduled.keys)
    }

    @Test fun debriefWithNothingLogged() {
        val text = DebriefComposer.compose(DayRecord(LocalDate(2026, 10, 21)), "sir", LocalDateTime(2026, 10, 21, 23, 55))
        assertTrue("nothing logged" in text)
    }

    @Test fun debriefMentionsProblemsAndTomorrow() {
        val day = DayRecord(
            date = LocalDate(2026, 10, 21),
            plan = listOf(PlanItem("DSA", PlanStatus.DONE), PlanItem("Physics lab")),
            entries = listOf(LogEntry(LocalTime(16, 0), LogCategory.PROBLEM, "Recursion is confusing")),
        )
        val text = DebriefComposer.compose(day, "sir", LocalDateTime(2026, 10, 21, 23, 55))
        assertTrue("Recursion is confusing" in text)
        assertTrue("starting tomorrow with Physics lab" in text)
    }
}
