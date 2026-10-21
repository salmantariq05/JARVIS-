package com.jarvis.shared

import com.jarvis.shared.brain.Intent
import com.jarvis.shared.brain.IntentParser
import com.jarvis.shared.data.DayOps
import com.jarvis.shared.data.PlanMatcher
import com.jarvis.shared.model.CallKind
import com.jarvis.shared.model.DayRecord
import com.jarvis.shared.model.LogCategory
import com.jarvis.shared.model.PlanStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IntentParserTest {
    private val parser = IntentParser()
    private val now = LocalDateTime(2026, 10, 21, 14, 0)
    private fun parse(text: String) = parser.parse(text, now)

    @Test fun scheduleWithLabel() {
        val intent = assertIs<Intent.ScheduleCall>(parse("Hey Jarvis, remind me at 5 pm to submit the DB assignment"))
        assertEquals(LocalDateTime(2026, 10, 21, 17, 0), intent.at)
        assertEquals("submit the DB assignment", intent.label)
    }

    @Test fun scheduleWithoutTimeAsksForIt() {
        assertEquals(Intent.MissingTime, parse("wake me up"))
    }

    @Test fun planIsSplitIntoItems() {
        val intent = assertIs<Intent.SetPlan>(parse("My plan for today is DSA linked lists, OS assignment and gym"))
        assertEquals(listOf("DSA linked lists", "OS assignment", "gym"), intent.items)
        val urdu = assertIs<Intent.SetPlan>(parse("aaj ka plan DSA aur physics phir gym"))
        assertEquals(listOf("DSA", "physics", "gym"), urdu.items)
    }

    @Test fun completedTask() {
        val intent = assertIs<Intent.Completed>(parse("I finished the OS assignment"))
        assertEquals("the OS assignment", intent.what)
        assertEquals(PlanStatus.DONE, intent.status)
        assertEquals(PlanStatus.DONE, assertIs<Intent.Completed>(parse("DSA done hai")).status)
        assertEquals(PlanStatus.PARTIAL, assertIs<Intent.Completed>(parse("mark gym as half done")).status)
    }

    @Test fun studyLogWithDuration() {
        val intent = assertIs<Intent.Log>(parse("I studied operating systems for 2 hours"))
        assertEquals(LogCategory.STUDY, intent.category)
        assertEquals(120, intent.minutes)
        assertEquals("operating systems", intent.subject)
    }

    @Test fun problemLog() {
        val intent = assertIs<Intent.Log>(parse("log: I'm confused about recursion"))
        assertEquals(LogCategory.PROBLEM, intent.category)
    }

    @Test fun dailyCallSettings() {
        val night = assertIs<Intent.SetDailyCall>(parse("move my night debrief call to 11:30"))
        assertEquals(CallKind.NIGHT_DEBRIEF, night.kind)
        assertEquals(LocalTime(23, 30), night.time)
        val off = assertIs<Intent.SetDailyCall>(parse("turn off the morning call"))
        assertEquals(false, off.enabled)
    }

    @Test fun conversationalIntents() {
        assertEquals(Intent.Goodbye, parse("thanks jarvis"))
        assertEquals(Intent.Goodbye, parse("ok goodnight"))
        assertEquals(Intent.Goodbye, parse("khuda hafiz"))
        assertEquals(Intent.Greeting, parse("Assalamualaikum"))
        assertEquals(Intent.Debrief, parse("summarize my day"))
        assertEquals(Intent.AskTime, parse("time kya hai"))
        assertEquals(Intent.ListPlan, parse("what's left"))
        assertEquals(Intent.CancelCalls, parse("cancel my alarms"))
    }

    @Test fun unknownGoesToTheBrain() {
        assertNull(parse("explain the difference between a process and a thread"))
    }

    @Test fun planMatcherFindsFuzzyMatches() {
        val titles = listOf("DSA linked lists", "Operating Systems assignment", "Gym")
        assertEquals(1, PlanMatcher.bestMatchIndex(titles, "the operating systems one"))
        assertEquals(0, PlanMatcher.bestMatchIndex(titles, "linked list"))
        assertEquals(2, PlanMatcher.bestMatchIndex(titles, "task 3"))
        assertEquals(1, PlanMatcher.bestMatchIndex(titles, "the second task"))
        assertNull(PlanMatcher.bestMatchIndex(titles, "physics"))
    }

    @Test fun dayOpsUpdatePlan() {
        val day = DayOps.setPlan(DayRecord(LocalDate(2026, 10, 21)), listOf("DSA", "OS assignment"))
        val (updated, item) = DayOps.updatePlanItem(day, "os", PlanStatus.DONE)
        assertEquals("OS assignment", item?.title)
        assertEquals(1, updated.doneCount)
    }
}
