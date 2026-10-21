# J.A.R.V.I.S. · Just A Rather Very Intelligent System

A personal AI voice assistant for Android: it **calls you** at the times you choose, keeps your **daily plan and study log**, rings you at night with a **debrief of your day**, and **tutors you** through your doubts. It runs on **Claude** when online and an **offline brain** when not.

> **Status: Phase 1 of 5.** See the [blueprint](docs/BLUEPRINT.md) for the full roadmap and the [Phase 1 lesson](docs/lessons/phase-1.md) to learn how every part works.

```
        ╭───────────────╮          "Hey Jarvis, call me at 7 AM"     → ⏰ schedules a real ringing call
      ╭─┤  ◎ ◎ ◎ ◎ ◎   ├─╮        "My plan for today is DSA and OS"  → 📋 saves today's plan
      │ │ ◎    ⚛    ◎ │ │        "I studied recursion for 2 hours"  → 📚 logs study time
      ╰─┤  ◎ ◎ ◎ ◎ ◎   ├─╯        11:55 PM  📞 RING RING              → 🌙 night debrief + doubt clearing
        ╰───────────────╯          "Kal subah saat baje utha dena"    → ⏰ understands Urdu/Pashto mix
```

## What works today (Phase 1)

| Feature | How it works |
|---|---|
| 📞 **Jarvis calls you** | Exact alarms ring a full-screen "Incoming call · J.A.R.V.I.S" screen, even on the lock screen. Answer and talk. |
| 🌙 **Night debrief call** (default 11:55 PM) | Reads back your plan (done, partly done, not done), study time per subject and problems you flagged, then clears your doubts like a tutor. |
| ☀️ **Morning plan call** (default 8:00 AM) | Asks for your plan and saves it. The night call checks it. |
| 🧠 **Two brains + auto-switch** | Claude (Haiku 5.5 for quick replies, Opus 5.5 for debriefs and tutoring). Falls back to the offline brain with no key, no internet or no credit. |
| 📴 **Offline brain** | Understands commands instantly for free, in English, Roman Urdu and Roman Pashto. Optional on-device AI model for free-form questions. |
| 🗣️ **Mixed-language input** | Understands Pashto, Urdu and English mixed together. Always replies in English. |
| 🎛️ **Iron Man HUD** | Animated arc reactor that reacts to your voice, live transcript, command center with plan, log and calls. |
| 🔐 **Private by design** | Your data stays on your phone. The API key is encrypted with Android Keystore. |

**Coming next:** "Hey Jarvis" wake word and voice lock (Phase 2); WhatsApp, Instagram, Snapchat, Telegram, Gmail and Outlook (Phase 3); study cards, quizzes and focus mode (Phase 4); morning briefing, phone control, developer mode and iPhone (Phase 5).

---

## 📲 Install on your Samsung (5 minutes)

1. On your phone, open **[Releases → jarvis-latest](../../releases/tag/jarvis-latest)** and tap **`jarvis.apk`** (about 80 MB, Android 12+).
2. When asked, allow your browser or **My Files** to *install unknown apps*. If Play Protect warns about an "unrecognised developer", tap **More details → Install anyway** (it's your own app).
3. Open Jarvis and allow **Microphone** and **Notifications**.
4. Tap **SETUP** and work through it top to bottom:
   - **Reliability · Samsung**: tap **FIX** on every ✗ and do the five Samsung steps listed there. *This is what makes the calls ring reliably.*
   - **Brain**: paste your Claude API key (see below), then **TEST**.
   - **Daily calls**: set your night debrief time, then tap **RING ME IN 10 SECONDS** and lock your phone to see the call screen.
5. Go back to the HUD, tap the glowing core and say: *"My plan for today is…"*

Updates: every push to GitHub builds a new APK. Download and install it over the old one; your data is kept.

### 🔑 Getting a Claude API key
Your Claude.ai subscription does **not** cover apps you build. The API is billed separately.
1. Go to **console.anthropic.com** → sign in → **Billing**: add a small prepaid credit (e.g. $5) and set a **monthly spend limit**.
2. **API keys → Create key** → copy it → paste it into Jarvis › SETUP › Brain → **SAVE** → **TEST**.

With the smart mix (Haiku for quick turns, Opus for debriefs), normal daily use is roughly **$8–15/month**. With no key, Jarvis runs fully offline at $0.

### 📴 Optional: offline AI model
The offline brain already handles calls, plans, logs and the debrief. To let it answer free-form questions without internet, download a **LiteRT-LM** model (`.litertlm` file) on your phone, for example a *Gemma 3 1B* model from [huggingface.co/litert-community](https://huggingface.co/litert-community) (about 0.5 GB; accept the Gemma licence first), then **SETUP › Offline AI model › IMPORT**.

---

## 🛠️ For developers (Windows / Mac / Linux)

1. Install **[Android Studio](https://developer.android.com/studio)** (it includes Java and the Android SDK).
2. `git clone https://github.com/salmantariq05/JARVIS-.git` → Android Studio → **Open** the folder → wait for Gradle sync.
3. Phone: Settings → About phone → Software information → tap **Build number** 7× → Developer options → **USB debugging** on. Plug in, then press ▶ **Run**.

```bash
./gradlew :shared:jvmTest              # 34 unit tests for the brain (no phone needed)
./gradlew :androidApp:assembleRelease  # builds androidApp/build/outputs/apk/release/androidApp-release.apk
```

### Project map
```
shared/                       ← pure Kotlin (Kotlin Multiplatform): will run on iPhone too
  commonMain/.../brain/       Brain interface, OfflineBrain, IntentParser, Prompts, BrainRouter, DebriefComposer
  commonMain/.../time/        TimeParser (English/Urdu/Pashto), CallPlanner, LogicalDay
  commonMain/.../data/        JarvisRepository, DayOps, PlanMatcher
  commonMain/.../JarvisAssistant.kt   the conductor: context → brain → actions → memory
  jvmMain/.../ClaudeBrain.kt  Claude via the official Anthropic SDK (structured JSON output)
androidApp/                   ← everything that needs Android
  calls/                      AlarmManager scheduling, ringing notification, IncomingCallActivity
  voice/                      SpeechInput (ears), VoiceOutput (voice), ConversationRunner (the loop)
  ui/hud, ui/settings         Jetpack Compose screens, ArcReactor animation
  data/SecretStore.kt         Android Keystore encryption for your API key
  brain/OnDeviceLlm.kt        offline LLM via Google LiteRT-LM
.github/workflows/build.yml   CI/CD: test → build → sign → publish APK
```

```mermaid
flowchart LR
  you([🗣️ You]) --> ears[SpeechInput]
  ears --> runner[ConversationRunner]
  runner --> assistant[JarvisAssistant]
  assistant --> router{BrainRouter}
  router -->|online + key| claude[ClaudeBrain<br/>Haiku / Opus]
  router -->|offline / error| offline[OfflineBrain<br/>rules + on-device AI]
  claude --> actions[[Actions:<br/>schedule call · log · plan]]
  offline --> actions
  actions --> repo[(JarvisRepository<br/>plans · logs · calls)]
  actions --> alarms[AndroidCallScheduler]
  alarms -->|time comes| call[📞 IncomingCallActivity]
  call --> runner
  runner --> voice[VoiceOutput 🔊]
```

## Honest limits
- Phase 1 has no wake word yet: tap the core (Phase 2 adds "Hey Jarvis" plus voice lock).
- Pashto speech recognition is weak on every phone today; English and Urdu work well. When Jarvis is unsure, it asks.
- Jarvis is sideloaded (not on Play Store) because Google restricts apps that read other apps' notifications and Gmail. That's normal for personal assistants like this.
