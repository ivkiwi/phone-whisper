<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper (with Roest Danish ASR Support)

Push-to-talk dictation for Android, featuring local, offline Danish voice input.

This repository is a fork of the excellent [Phone Whisper](https://github.com/kafkasl/phone-whisper) by [@kafkasl](https://github.com/kafkasl), modified to add native support for the Danish **Roest Wav2Vec2** speech recognition model.

Phone Whisper lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.\

It supports:

- **Local on-device transcription** with sherpa-onnx (including Danish via Roest)
- **Cloud transcription** with an OpenAI-compatible provider
- **Optional cleanup** with a selected OpenAI-compatible chat model

If you try it and it genuinely saves you time, please consider [sponsoring the original author, @kafkasl](https://github.com/sponsors/kafkasl).

## Key Enhancements & Memory Optimizations

In addition to Danish language support, this fork includes crucial memory management enhancements to prevent Out-Of-Memory (OOM) crashes when running larger on-device models:

- **Automatic Idle Model Unloading**: The local transcription model is loaded dynamically and unloaded automatically after 2 minutes of inactivity, freeing up system memory.
- **Asynchronous Lazy Pre-loading**: Models are loaded asynchronously in the background. When you tap the recording button, the app checks and triggers pre-loading if needed so that it's ready by the time you finish speaking.
- **Thread-Safe Resource Release**: The `LocalTranscriber` cleanly releases native `sherpa-onnx` resources when unloading or when the Accessibility Service is destroyed.
- **Large Heap Flag Enabled**: Configured `android:largeHeap="true"` in the Android Manifest to accommodate larger model requirements.

## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest APK from [GitHub Releases](https://github.com/kafkasl/phone-whisper/releases).

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/ArtificialTruth/phone-whisper.git && cd phone-whisper
make build
```

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is transcribed locally or in the cloud
5. The text is inserted into the focused text field
6. If insertion fails, the text is copied to the clipboard

## Setup

### First-time setup

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud transcription**: set a transcription API key/base URL, then choose a model fetched from that provider
5. Optional cleanup uses its own API key/base URL/model, separate from cloud transcription

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

Phone Whisper supports two modes:

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to the configured transcription API
- **Optional cleanup**: transcript text is sent directly from your device to the configured chat API

I don't run a backend for this app. In cloud mode, requests go straight from your phone to the configured OpenAI-compatible providers using your own API keys and selected models.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.kafkasl.phonewhisper/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| Roest Danish Wav2Vec2 | 315 MB | Offline Danish (wav2vec2 CTC support) |
| Parakeet 110M | 100 MB | Best default |
| Whisper Base | 199 MB | Solid baseline |
| Parakeet 0.6B | 465 MB | Best quality |
| Moonshine Tiny | 103 MB | Fastest |

The app downloads and extracts models directly from the sherpa-onnx release archives, or from the Roest release for Danish support.

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

## App compatibility

Phone Whisper works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Phone Whisper falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Phone Whisper in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Cloud mode requires your own API key for the configured OpenAI-compatible provider

## Support the project

If Phone Whisper saves you time, you can sponsor the project on GitHub:

- https://github.com/sponsors/kafkasl

## License

This application's source code is licensed under the original personal/permissive terms: "Personal project. Do whatever you want with it." (respecting the original creator's license).

### Model Licenses

If you use the local Danish model (**Roest Danish Wav2Vec2**), please note that it was trained as part of the CoRal project by the [Alexandra Institute](https://www.alexandra.dk/). The model is licensed under a custom license adapted from **OpenRAIL-M** (available [here](https://huggingface.co/Alvenir/coral-1-whisper-large/blob/main/LICENSE)). 

In summary, the OpenRAIL-M license allows free access and commercial use, but includes specific use-based restrictions:
1. You may not use the model to violate any law, exploit or harm minors, defame/harass, generate false information to harm others, or discriminate.
2. Under the custom terms of the CoRal project, you specifically agree not to:
   - Impersonate any person or entity or create synthetic speech emulating a specific natural person.
   - Use the model to detect or infer aspects/features of an identity of any natural persons (such as name, gender, age, health, etc.).
