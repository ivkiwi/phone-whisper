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

## Install

Use the side-by-side debug build from [Releases](https://github.com/ivkiwi/phone-whisper/releases/tag/openai-compatible-providers-side-by-side-2026-06-29).

It installs next to upstream `0.4.1`:

- package id: `com.kafkasl.phonewhisper.openai`
- app label: `Phone Whisper OpenAI`

## Status

Built by GitHub Actions. APK ready:

[phone-whisper-openai-side-by-side-debug.apk](https://github.com/ivkiwi/phone-whisper/releases/download/openai-compatible-providers-side-by-side-2026-06-29/phone-whisper-openai-side-by-side-debug.apk)
