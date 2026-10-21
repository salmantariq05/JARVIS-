# Phase 2 lesson: "Hey Jarvis", always listening, only to you

> Same format as Phase 1: one ⚡ block = one 25-minute focus session. Each one gives the idea in one sentence, where it lives in Jarvis, and something to try.

**Progress:** ☐1 ☐2 ☐3 ☐4 ☐5 ☐6 ☐7 ☐8 ☐9

---

## 🗺️ The 30-second map

```
🎙️ mic (16,000 samples/sec)
  │  every 100 ms
  ▼
[ring buffer: last 2 s] ─────────────────────────────┐
  │                                                   │ on detection: the "Hey Jarvis" clip
  ▼                                                   ▼
[energy gate] ──quiet? skip──►  (saves battery)   [trim silence] ─► [TitaNet voiceprint] ─► cosine vs your profile
  │ someone's talking                                                            │
  ▼                                                                    ≥ threshold? ──no──► ignored ("not your voice")
[keyword spotter: "Hey Jarvis" / "Jarvis"] ── detected ──►                       │ yes
                                                                                 ▼
                                                    🔔 chime ─► normal Jarvis chat (Phase 1 pipeline)
```
Files: `androidApp/.../voice/wake/` (Android side) and `shared/.../voice/` (pure maths, unit-tested).

---

## ⚡1 Foreground services: Android's privacy contract
**One sentence:** an app may only use the mic in the background if it shows a permanent notification, so you always know.
- `ListeningService` declares `android:foregroundServiceType="microphone"` and calls `startForeground(...)`.
- Android 14+ only lets you **start** microphone use while the app is on screen. That's why Jarvis restarts listening in `MainActivity.onResume()`, and after a reboot it posts *"Tap to turn Hey Jarvis back on"* (see `BootReceiver`).
- The green privacy dot in the status bar is Android telling you the mic is on. Good design never hides it.
**Read:** [Foreground services](https://developer.android.com/develop/background-work/services/fgs)

## ⚡2 Digital audio in 5 minutes
- **Sample rate 16 kHz** = 16,000 numbers per second. Speech models use 16 kHz because voices live below 8 kHz (Nyquist: sample at twice the highest frequency).
- The mic gives `Short` values (−32768..32767). We divide by 32768 to get floats in −1..1.
- **RMS** (root-mean-square) = loudness. See `rms()` in `shared/.../voice/AudioDsp.kt`.
- **Frames:** we process 100 ms chunks (1,600 samples), small enough to react fast and big enough to be efficient.

## ⚡3 Keyword spotting: a tiny speech recognizer that only knows one phrase
**One sentence:** a 3.3-million-parameter streaming model (Zipformer transducer) listens for a list of sub-word tokens.
- Words are split into **BPE tokens** from the model's vocabulary: `HEY JARVIS → ▁HE Y ▁JA R VI S` (computed with `sentencepiece` and the model's `bpe.model`).
- `assets/kws/keywords_both.txt`: `▁HE Y ▁JA R VI S :2.0 #0.2 @HEY_JARVIS`, where `:2.0` boosts the phrase, `#0.2` is the trigger threshold, and `@HEY_JARVIS` is the name reported back.
- **Measured before shipping** (synthetic voices, 20 speakers):

| Setting | "Hey Jarvis"/"Jarvis" detected | False alarms on 100 look-alikes ("hey Travis", "harvest", "hey Siri", "Marvin"…) |
|---|---|---|
| score 2.0, threshold 0.2 (shipped) | 55/60 (92%) | **0** |

- **Real bug caught by testing first:** the "mobile" version of the model crashed in sherpa-onnx 1.13.8 (`Reshape` error), even on its own sample audio. We switched to the standard model, which worked. If we'd shipped without testing, "Hey Jarvis" would have crashed on your phone.
**Try it:** add "OK Jarvis". Its tokens are `▁O K ▁JA R VI S`. Add a line to both keyword files and rebuild.

## ⚡4 Energy gate + ring buffer: battery and the first syllable
- **Energy gate** (`EnergyGate`): learns your room's background noise and only wakes the neural network when something is clearly louder. In a quiet room the model barely runs. Real assistants use a "VAD" (voice activity detector) the same way.
- **Ring buffer** (`FloatRingBuffer`): always holds the last 2 seconds. When speech starts we feed 300 ms from *before* it, so the "H" of "Hey" isn't cut off. When the wake word fires, the clip for the voice lock is already there.
- Both are pure Kotlin with unit tests (`VoiceLockTest.kt`): no phone needed to prove they work.

## ⚡5 Embeddings: turning a voice into 192 numbers
**One sentence:** a neural network maps ~1 s of speech to a vector that captures *how* you sound, not *what* you said.
- Same person → vectors point the same way. Compare with **cosine similarity** (1 = same direction, 0 = unrelated): `VoiceMath.cosine()`.
- **Enrollment** = 8 samples of you saying "Hey Jarvis" → normalise each → average = your **centroid** (`VoiceProfile`).
- **Leave-one-out check:** compare each sample with the average of the other 7. Low scores mean noisy recordings, so Jarvis tells you to re-enroll.
- This idea (embeddings + cosine) also powers face unlock, Spotify recommendations and semantic search with LLMs. Learn it once, use it everywhere.

## ⚡6 Evaluate like a scientist (the most important block)
Everything here was measured, not assumed.

**Trap 1, synthetic voices:** I first tested the voice lock with 20 text-to-speech voices. Strangers scored 0.93–0.98, higher than the owner! All TTS voices from one synthesizer sound like the same "speaker" to a model trained on real humans. **Lesson: test data must match real-world data.**

**Trap 2, mislabelled data and silence:** on real recordings, the first numbers were nonsense until I fixed two test bugs: two different people who shared the label "speaker1", and silent clips (silence "sounds" the same for everyone).

**Choosing the model:** same real recordings, three candidate models:

| Model | Same person (avg similarity) | Different people (avg) | |
|---|---|---|---|
| 3D-Speaker CAM++ | 0.68 | 0.39 | ❌ |
| WeSpeaker ResNet34 | 0.88 | 0.63 | ⚠️ everyone looks alike |
| **NeMo TitaNet-small** | **0.72** | **0.14** | ✅ chosen |

**Choosing the threshold** (TitaNet, 1.2 s clips, the length of "Hey Jarvis"):

| Threshold | Accepts you (true accept) | Lets others in (false accept) |
|---|---|---|
| 0.30 Relaxed | 95% | 2.9% |
| **0.35 Balanced** | **95%** | **2.1%** |
| 0.40 Strict | 89% | 0.4% |

That's the **FAR/FRR trade-off** (false accept rate vs false reject rate). Every biometric system, including Face ID and fingerprint readers, picks a point on this curve. It's the *Strictness* setting in Jarvis.
**Honest limits:** the real test set was small (7 people) and mostly not "Hey Jarvis" audio. Your own enrollment uses the exact phrase, which usually *improves* accuracy, but only your phone can prove it. Use **TEST MY VOICE** and watch the scores on the HUD.

## ⚡7 Concurrency: one microphone, many users
- The listener runs on its **own thread** (`KeywordListener`), because audio can't wait for the UI. Flags shared between threads are `@Volatile`.
- `MicArbiter` counts "holds": HUD chats, Jarvis calls and enrollment each *hold* the mic, and the wake-word listener pauses until the count is back to 0. This is like a **semaphore** from your OS course, but non-blocking.
- `SpeakerVerifier.embed()` is `@Synchronized`: the native model isn't thread-safe, so only one thread may use it at a time.

## ⚡8 Supply-chain security: trust, but verify
Jarvis downloads three things it didn't write: the sherpa-onnx library, the wake-word model and the voice model. Each one's **SHA-256 fingerprint is pinned** in the code (`DownloadVerified` in `androidApp/build.gradle.kts`, `VoiceModels.downloadSpeakerModel`). If a single byte changes, the build fails or the app refuses the file. I tested this on purpose with a wrong fingerprint, and the build stopped with *"Checksum mismatch"*.
**Interview Q:** *"How do you protect a build from a compromised dependency?"* (Pin versions and checksums, verify signatures, minimise dependencies, review updates.)

## ⚡9 Security honesty: what a voice lock is and isn't
- ✅ Stops your friends, family and the TV from bossing Jarvis around.
- ❌ Not a password: a good recording of your voice could fool it. Phase 3 will require an extra confirmation (fingerprint or a confirm phrase) before Jarvis **sends messages** for you. Sessions already record `verifiedSpeaker` for this.
- 🔒 Privacy: the voiceprint is 192 numbers stored only on your phone (`voice/profile.json`). **DELETE VOICEPRINT** removes it.

---

## 📲 How to try Phase 2 on your phone
1. Install the new `jarvis.apk` from Releases (it updates over Phase 1 and keeps your data).
2. **SETUP → Hey Jarvis · Voice lock → SET UP VOICE LOCK → DOWNLOAD VOICE MODEL (40 MB)** → START → say "Hey Jarvis" 8 times.
3. Tap **TEST MY VOICE** a few times. Then ask a friend to try. Compare the scores.
4. Turn on **Always listening**. Lock the phone, say **"Hey Jarvis"**, wait for the **chime**, then talk.
5. Watch the HUD's **HEY JARVIS** panel: it shows "Recognised you (match 0.62)" or "Ignored a voice that isn't yours (match 0.12)".

## 🏋️ Exercises
1. **Easy:** add "OK Jarvis" as a wake phrase (⚡3).
2. **Easy:** show the last 10 match scores on the HUD and look for your personal threshold.
3. **Medium:** count how often the energy gate is open per hour and show "model active 4% of the time" (that's a battery metric).
4. **Medium:** swap the energy gate for sherpa-onnx's Silero VAD (`Vad` class is in the same AAR) and compare.
5. **Hard:** after "Hey Jarvis", keep recording with our own `AudioRecord` and stream that audio into Android's recognizer (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, Android 13+), so you can say *"Hey Jarvis call me at 7"* in one breath without waiting for the chime.

## 📚 Resources
| Topic | Link |
|---|---|
| sherpa-onnx docs (wake word, speaker ID, VAD) | [k2-fsa.github.io/sherpa/onnx](https://k2-fsa.github.io/sherpa/onnx/index.html) |
| How Apple built "Hey Siri" (great read) | [machinelearning.apple.com/research/hey-siri](https://machinelearning.apple.com/research/hey-siri) |
| Android foreground services | [developer.android.com/…/fgs](https://developer.android.com/develop/background-work/services/fgs) |
| Embeddings and cosine similarity, visually | [3Blue1Brown, Neural networks series](https://www.3blue1brown.com/topics/neural-networks) |
| Tokenization (BPE / SentencePiece) | [github.com/google/sentencepiece](https://github.com/google/sentencepiece) |

**Next lesson (Phase 3):** notification listeners, OAuth 2.0, the Gmail & Microsoft Graph APIs, and privacy-first message triage.
