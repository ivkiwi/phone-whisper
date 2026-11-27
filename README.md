# Phone Whisper BYOK

Fork of a fork. Tremendous lineage, tremendous app, but now with freedom. Real freedom.

This build adds the thing cloud dictation always needed:

- **BYOK**: bring your own API key.
- **Any OpenAI-compatible HTTPS endpoint**: OpenAI, OpenRouter, self-hosted proxy, whatever speaks the protocol.
- **Cloud transcription can replace the local model completely**: choose provider, key, base URL, model.
- **Cleanup/postprocessing is separate**: its own provider, own key, own base URL, own chat model. No mixing. No confusion. Beautiful separation.
- **Model choice comes from your endpoint**: the app asks `/models` and shows what your provider actually has.

Old way: one hardcoded OpenAI-shaped path. Very limited. Sad.

New way: transcription provider here, cleanup provider there, keys stay yours, models come from the endpoint. Strong architecture. Many people are saying this.

## Voice keyboard

No accessibility service needed: enable **Phone Whisper voice** in Android keyboard settings (or from the app's Setup section) and switch to it in any text field. Bank apps that block accessibility services stay happy.

- **Voice activity detection** for streaming models: a bundled Silero VAD lets only speech reach the recognizer, so noise and silence do not turn into invented words, and each pause closes a segment. Toggle under *Local models*.
- **Push-to-talk** (teal mic, streaming local models only): hold to talk, release to finish. In the app, *Keyboard mic buttons* chooses tap mic, push-to-talk or both side by side; without a streaming model the tap mic is shown.
- **Mic** in the middle: tap to start, tap to stop. Text goes in through the normal keyboard channel, with smart spacing and capitalization.
- **Smart punctuation** `, . ? !`: replaces the mark the recognizer guessed ("Ты придёшь." + `?` → "Ты придёшь?"). Press two marks in a row to stack them (`?!`, `...`).
- **Fix a word**: the last phrase appears as tappable words above the mic. Tap one to select it, then:
  - **✨ Suggestions** — the cleanup LLM proposes what you probably said (uses the Post-Processing provider and key);
  - **🎤 Again** — re-dictate just that word;
  - **Aa** — flip capitalization; **Delete**; **⌨ Type** — jump to your regular keyboard with the word selected.
- **Live streaming**: with a streaming local model (Vosk) text appears while you speak — the current piece underlined, fixed on each pause. Punctuation keys work mid-dictation: they close the current piece and place the mark, so the next word after `.?!` starts with a capital. Other keys stop dictation first. With cleanup on, the whole phrase is replaced by the cleaned version after you stop.
- **← →** move by word, **⌫ word** deletes a word, ⌫ and arrows repeat when held.
- **Slide on the space bar** to move the cursor by characters; a tap types a space.
- Keyboard key: tap to return to your previous keyboard, hold for the list.
- **Voice input for other keyboards**: the keyboard registers as a voice-input method, so the mic key of HeliBoard and other AOSP-style keyboards opens it (enable their voice key if hidden); the keyboard key brings you back.

The keyboard and the floating button share one dictation engine, so the local model is loaded once.

## Voice input for other apps and keyboards

Like FUTO Voice Input, Phone Whisper answers Android's standard speech request
(`android.speech.action.RECOGNIZE_SPEECH`): apps and keyboards that offer a mic through it
(AnySoftKeyboard, the AOSP keyboard and others) get a floating window that starts listening at
once, shows the text as you speak, stops on **Done** or after a pause, and returns the text.
Its model and mode come from one of your voice buttons (*Voice input for other apps* in the app).
Keyboards with a mic key for a voice IME (HeliBoard, FlorisBoard) open the Phone Whisper voice
keyboard instead.

Apps using the **SpeechRecognizer API** (no window) can use Phone Whisper too: pick it as the
system speech recognition service (Android settings → System → Languages → Voice input /
Speech recognition, depending on the device). It sends partial results while you speak, ends
after a pause, and opens the microphone on behalf of the calling app.

## Install

Get `phone-whisper.apk` from the [latest release](https://github.com/ivkiwi/phone-whisper/releases/latest).

It installs next to upstream `0.4.1`:

- package id: `com.kafkasl.phonewhisper.openai`
- app label: `Phone Whisper OpenAI`

After that the app updates itself. GitHub Actions builds every push to `main` and publishes it as release `build-<n>`. *App updates* at the top of the app downloads it and opens the system installer.

## Local models

Besides the upstream list: **GigaAM v4 RNN-T** (Russian + English, punctuation). There is no sherpa-onnx conversion of it on Hugging Face yet, so ours is in the [models-gigaam-v4](https://github.com/ivkiwi/phone-whisper/releases/tag/models-gigaam-v4) release, with the export script.
