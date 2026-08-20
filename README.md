# Interview Mitra

Native Android MVP/V2 for real-time mock interview practice. It captures mixed mono audio in a microphone foreground service, streams diarized transcription through Deepgram, manually maps the candidate speaker during calibration, and sends only reconstructed interviewer questions to Claude for streamed suggested answers.

## Run it

1. Open this folder in Android Studio and select a device running Android 8 (API 26) or newer.
2. Copy `local.properties.example` to `local.properties`, set `sdk.dir`, `DEEPGRAM_API_KEY`, and `ANTHROPIC_API_KEY`.
3. Build and run. Allow microphone access. Uploading a PDF/text resume is optional.

Without API keys, the UI remains usable for setup and shows a clear placeholder after a detected question rather than contacting a service.

## Privacy boundary

The candidate speaker ID is established in calibration. Candidate transcript lines are displayed locally, but only interviewer-role chunks enter the reconstruction and question pipeline. The Claude request includes the resume profile, capped prior question/answer exchanges, and the current interviewer question—never candidate audio or transcript.
