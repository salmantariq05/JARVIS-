# Phase 1 lesson: how Jarvis works (and what you're learning)

> **How to use this lesson (ADHD-friendly):** one ⚡ block = one 25-minute focus session. Each block has the idea in one sentence, an analogy, the exact file in Jarvis, a 5-minute "try it" and an interview question. Tick the boxes as you go; the ticks are the dopamine 😉

**Progress:** ☐1 ☐2 ☐3 ☐4 ☐5 ☐6 ☐7 ☐8 ☐9 ☐10 ☐11 ☐12

---

## 🗺️ The 30-second map

```
You speak ─► SpeechInput ─► ConversationRunner ─► JarvisAssistant ─► BrainRouter ─┬─► ClaudeBrain (online)
                                    ▲                    │                        └─► OfflineBrain (offline)
                                    │                    ▼
                               VoiceOutput ◄──── speech + actions ──► Repository (plan/log) + CallScheduler (alarms)
```
Everything below is one box of this picture, zoomed in.

---

## ⚡1 Kotlin Multiplatform: write once, run on Android and iPhone
**One sentence:** put pure logic in `shared/` (no Android imports) and platform-specific code in `androidApp/` (later `iosApp/`).
**Analogy:** the *brain* is the same in any body; only the *hands and ears* differ per body.
**In Jarvis:** `shared/src/commonMain/` = brain (works on any platform). `shared/src/jvmMain/ClaudeBrain.kt` = JVM-only piece. `androidApp/` = mic, alarms, screens.
**Try it (5 min):** open `JarvisAssistant.kt`. Search for `import android`: there are zero. That's why it can run on iPhone later.
**Interview Q:** *"What code would you share between Android and iOS, and what wouldn't you?"* (Business logic, models and networking yes; UI frameworks and OS services like alarms or notifications are platform code behind an interface.)

## ⚡2 Interfaces + the Strategy pattern: swappable brains
**One sentence:** an *interface* is a promise ("I can `reply()`"); any class keeping the promise can be plugged in.
**Analogy:** a phone charger port: any charger fitting USB-C works, and the phone doesn't care who made it.
```kotlin
interface Brain { suspend fun reply(request: BrainRequest): BrainReply }
class ClaudeBrain(...) : Brain   // online, smart, costs money
class OfflineBrain(...) : Brain  // offline, free, simpler
```
**In Jarvis:** `brain/Brain.kt`, `ClaudeBrain.kt`, `OfflineBrain.kt`. Tests even plug in a *fake* brain (`JarvisAssistantTest.kt`, `claudeActionsAreExecuted`).
**Try it:** in `JarvisAssistantTest`, the `broken` brain always throws. Read `fallsBackWhenClaudeFails`: that's how we tested "credit ran out" without spending a cent.
**Read more:** [refactoring.guru: Strategy](https://refactoring.guru/design-patterns/strategy)
**Interview Q:** *"How would you make a payment system support PayPal and Stripe?"* (Same pattern: `PaymentProvider` interface.)

## ⚡3 Graceful degradation: never just break
**One sentence:** when a dependency fails, fall back to a simpler path instead of crashing.
**In Jarvis:** `BrainRouter.kt`: no key / no internet / API error → offline brain, and Jarvis tells you once per conversation why.
**Interview Q:** *"Your app's backend is down. What should users see?"*

## ⚡4 The Repository pattern + thread safety
**One sentence:** one class owns all reading and writing of data; everyone else asks it.
**Analogy:** a librarian. You don't walk into the archive; you ask the librarian.
**In Jarvis:** `data/JarvisRepository.kt`. It stores JSON files via a `TextStorage` interface (`FileTextStorage` on Android, `InMemoryTextStorage` in tests). A `Mutex` stops two coroutines writing the same file at once (a *race condition*).
**Bonus:** `FileTextStorage.write` writes to a `.tmp` file and then renames it, which is an **atomic write**. A crash mid-save can't corrupt your data.
**Interview Q:** *"What's a race condition and how do you prevent one?"*

## ⚡5 Pure functions & unit tests
**One sentence:** a *pure* function depends only on its inputs (no files, network or clock), so it's trivial to test.
**In Jarvis:** `DayOps`, `PlanMatcher`, `TimeParser`, `DebriefComposer` are all pure. Note the **injected clock**: `JarvisClock` lets tests pretend it's 11:55 PM on any day.
**Try it:** run the tests (`./gradlew :shared:jvmTest`, or the green ▶ next to a test in Android Studio). Then *break* something on purpose: change `DAY_STARTS_AT_HOUR = 4` to `0` and watch `logicalDayRollsAtFourAm` fail. Breaking things on purpose is the fastest way to learn what a test protects.
**Real story from building this:** the first test run had 4 failures. One was a genuine bug: "mark gym as **half** done" was read as **done**, because rules were checked in the wrong order. Tests found it before you ever did.

## ⚡6 Regular expressions: understanding "kal saade saat baje"
**One sentence:** a regex is a tiny pattern language for finding text.
```
\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b
│  └hour─┘  └─optional :minutes─┘ └am/pm┘
```
**In Jarvis:** `time/TimeParser.kt` handles English ("7:30 pm", "in 10 minutes"), Urdu ("saade saat baje" = 7:30, "paune saat" = 6:45), and Pashto number words ("owa bajo" = 7 o'clock).
**Try it:** paste a pattern from `TimeParser.kt` into [regex101.com](https://regex101.com) and test your own sentences. Then add a word you actually use and write a test for it.
**Design lesson:** for "at 7" with no AM/PM, Jarvis picks the *next sensible* 7 o'clock. That's a product decision written as code.

## ⚡7 Coroutines: waiting without freezing
**One sentence:** `suspend` functions can *pause* (waiting for the mic, the network or TTS) without blocking the screen.
**In Jarvis:** `ConversationRunner` reads like a story because of coroutines:
```kotlin
val heard = listen()                       // pauses until you finish speaking
val turn = assistant.respond(session, heard) // pauses for Claude
voice.speak(turn.speech)                     // pauses until Jarvis stops talking
```
**Bridging old callbacks:** Android's speech recognizer uses callbacks. `SpeechInput.kt` wraps it with `suspendCancellableCoroutine`: a very common real-world pattern.
**Read more:** [Kotlin coroutines guide](https://kotlinlang.org/docs/coroutines-guide.html)

## ⚡8 Android system components: how a call actually rings
```
AlarmManager.setAlarmClock(7:00) ──► (phone asleep) ──► 7:00 CallAlarmReceiver.onReceive()
   ──► CallNotifier.ring(): CallStyle notification + full-screen intent + looping ringtone
   ──► IncomingCallActivity (shows over the lock screen) ──► you tap ANSWER ──► ConversationRunner.call()
```
- **BroadcastReceiver**: code that wakes up for a system event (alarm fired, phone rebooted). See `calls/CallReceivers.kt`.
- **PendingIntent**: a permission slip that lets the system run your code later.
- **Why `BootReceiver`?** Android forgets all alarms when the phone restarts, so we re-arm them.
- **Samsung battery saver** can still kill apps; that's why Setup has a Reliability checklist.
**Read more:** [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms/schedule) · [dontkillmyapp.com/samsung](https://dontkillmyapp.com/samsung)

## ⚡9 Jetpack Compose: UI as a function of state
**One sentence:** you describe what the screen looks like *for a given state*; Compose redraws when the state changes.
**Unidirectional data flow:** `ViewModel` holds `StateFlow` → screen observes it with `collectAsStateWithLifecycle()` → user taps → ViewModel function → state changes → screen redraws.
**In Jarvis:** `ui/hud/HudViewModel.kt` + `HudScreen.kt`. The arc reactor (`ui/components/ArcReactor.kt`) is pure `Canvas` drawing: circles, arcs, a rotating triangle, all maths with `cos`/`sin`. Its spin speed changes with the phase (thinking = fast amber).
**Try it:** change `Phase.THINKING -> 160f` to `600f` and rebuild. Then change `JarvisColors.Cyan` to your favourite colour.
**Course:** [Android Basics with Compose](https://developer.android.com/courses/android-basics-compose/course) (free, official).

## ⚡10 Security: protecting your API key
**One sentence:** never store secrets in plain text; encrypt them with a key the attacker can't get.
**In Jarvis:** `data/SecretStore.kt` encrypts the key with **AES-256-GCM**. The AES key itself lives inside **Android Keystore** (secure hardware), which won't let it be exported. The repo is public, but no secret is ever committed.
**Interview Q:** *"Where would you store an auth token on Android?"*

## ⚡11 LLM engineering: making Claude reliable inside an app
Four techniques used in `ClaudeBrain.kt` and `Prompts.kt`:
1. **Structured outputs:** Claude must reply in a strict JSON schema (`speech`, `actions`, `end_conversation`), so the app can safely *act*, not just talk.
2. **The model proposes, the app executes:** Claude can only request actions from a fixed list (`SCHEDULE_CALL`, `LOG`…), and `JarvisAssistant.execute()` validates each one (e.g. rejects times in the past).
3. **Model routing for cost:** `ModelRouter` sends "what time is it" to cheap, fast Haiku and "explain recursion" to Opus.
4. **Prompt caching:** the long system prompt never changes, so it's marked cacheable. Repeat requests are cheaper and faster.
**Also:** each request is *self-contained* (context + recent conversation in one message), which makes switching models mid-conversation safe.

## ⚡12 CI/CD: from `git push` to your phone
**One sentence:** a robot builds, tests and publishes your app on every push.
**In Jarvis:** `.github/workflows/build.yml`: checkout → Java 21 → run 34 tests → build the APK → sign it → publish it as the **jarvis-latest** release.
**Signing:** Android only installs updates signed with the *same key*. Jarvis uses a shared dev key so updates "just work". For a private key: run `keytool -genkeypair -keystore release.jks -alias jarvis -keyalg RSA -validity 10000`, base64 it, and add repository secrets `JARVIS_KEYSTORE_BASE64`, `JARVIS_KEYSTORE_PASSWORD`, `JARVIS_KEY_ALIAS` and `JARVIS_KEY_PASSWORD`. Switching keys means uninstalling once.
**Real story:** this machine couldn't reach Google's download servers, so GitHub's build machines compiled the Android app and reported the errors back. A remote CI loop like this is how many teams work.

---

## 🏋️ Exercises (pick one per day)
1. **Easy:** add your own greeting to `GREETING` in `IntentParser.kt` and a test for it.
2. **Easy:** make the night debrief also say how many log entries you made.
3. **Medium:** add an intent "how much did I study today?" that answers from `day.studyMinutes`. Test first, then code (TDD).
4. **Medium:** add a fourth HUD stat panel "STREAK". Hint: `repository.recentDays(30)`.
5. **Hard:** make "remind me every day at 6 to pray" create a *repeating* call. You'll touch models, the parser, the assistant and the scheduler: a real full-stack feature.

## 🧠 Learning strategy that beats 99% of people
- **Active recall > re-reading:** after each ⚡ block, close the file and explain it out loud. Jarvis's night debrief will do this with you.
- **Spaced repetition:** revisit each block after 1, 3, 7 and 14 days (Phase 4 automates this).
- **Build → break → fix:** never just read code; change it and see what happens.
- **Pomodoro for ADHD:** 25 minutes of focus, 5 minutes of movement. One ⚡ per pomodoro.
- **Public proof of work:** this repo is on your GitHub. Recruiters read READMEs, so keep it updated.

## 📚 Resources (all free)
| Topic | Link |
|---|---|
| Kotlin language | [kotlinlang.org/docs](https://kotlinlang.org/docs/home.html) · [Kotlin Koans](https://play.kotlinlang.org/koans/overview) (interactive exercises) |
| Android + Compose | [Android Basics with Compose](https://developer.android.com/courses/android-basics-compose/course) · [Compose docs](https://developer.android.com/develop/ui/compose/documentation) |
| Real-world reference app | [Now in Android](https://github.com/android/nowinandroid) (Google's best-practice sample) |
| Kotlin Multiplatform | [kotlinlang.org/docs/multiplatform.html](https://kotlinlang.org/docs/multiplatform.html) |
| Design patterns | [refactoring.guru](https://refactoring.guru/design-patterns) |
| Regex playground | [regex101.com](https://regex101.com) |
| Android career roadmap | [roadmap.sh/android](https://roadmap.sh/android) |
| Claude API | [platform.claude.com/docs](https://platform.claude.com/docs) |
| GitHub Actions | [docs.github.com/actions](https://docs.github.com/actions) |

**Next lesson (Phase 2):** foreground services, audio pipelines, wake-word detection and voice biometrics.
