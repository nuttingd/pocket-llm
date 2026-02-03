<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" alt="Pocket LLM icon" />
</p>

# Pocket LLM

A private LLM chat client for Android. Connect to any OpenAI-compatible API (Ollama, LM Studio, OpenAI, etc.) and chat from your phone with full conversation history.

## Features

- **OpenAI-compatible API** — Works with Ollama, LM Studio, OpenAI, and any server exposing the `/v1/chat/completions` endpoint
- **Streaming responses** — Real-time token-by-token output via SSE
- **Conversation management** — Create, rename, delete, search, and export conversations
- **Per-conversation model selection** — Each conversation remembers its server and model
- **Message branching** — Regenerate, edit, and navigate alternate response branches
- **Image attachments** — Send images to vision-capable models with automatic compression
- **Markdown rendering** — Rich formatting in assistant responses with syntax highlighting
- **Thinking sections** — Collapsible display of reasoning model chain-of-thought
- **Tool calling** — Calculator, web fetch, and extensible tool framework with approval UI
- **Context compaction** — Automatic and manual conversation summarization to stay within context limits
- **Parameter presets** — Save and apply temperature, top-p, and other generation settings
- **Encrypted API keys** — Stored with Tink/AES on-device encryption
- **Material 3** — Dynamic color, light/dark theme, configurable font size

## Building

Requires JDK 21+ and Android SDK 36. Min SDK is 28 (Android 9).

```sh
./gradlew assembleDebug
```

For a signed release build, create `keystore.properties` in the project root (gitignored):

```properties
storeFile=release.jks
storePassword=your-store-password
keyAlias=pocket-llm
keyPassword=your-key-password
```

Then:

```sh
./gradlew assembleRelease
```

## Testing

```sh
./gradlew test
```

## License

[MIT](LICENSE)
