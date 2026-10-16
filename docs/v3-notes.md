# V3 batch notes engine

Version 0.3.0, Room schema 4, 16 October 2026. The user selected **OpenRouter only, batch transcription for now**. Capture keeps the existing MediaRecorder path; no recording-time inference is presented as live.

## User flow

Save a recording, open its detail and create notes. Select one of seven templates and optionally wait for charging. The dialog discloses cloud upload, billing and audio retention. Completed transcript/notes are readable while later chunks process. Pause preserves committed work; explicit resume uses current model settings for unfinished requests.

Quick, Standard, Detailed and Study are views of the same normalized records. Open exact evidence, edit/highlight notes, inspect revision history, mark actions complete, rename/collapse map topics and refresh one topic from saved evidence. Manual text is protected; proposed replacements become unapplied AI candidates. Transcript search loads pages of 100 segments. Provider-timed chapters open notes/transcript and seek retained audio. Android's document picker exports Markdown and evidence.

No-speech results retain audio and offer an explicitly disclosed retranscription attempt. Interrupted recordings are partial and retain audio. Automatic removal requires nonempty finalized notes committed locally and the enabled retention option. Cleanup failures display a warning and can be retried without a model request.

## Processing bounds

- Unique WorkManager jobs require network and adequate battery, optionally charging. Room stores model selections, cursors, chunk fingerprints/completion and snapshots. The process gate excludes recording, recovery and processing from overlapping.
- MediaExtractor/MediaMuxer prepares one compressed two-minute AAC/M4A slice at a time, without PCM decode or a full-audio RAM buffer. Multipart upload streams it; `finally` removes the temporary file.
- Transcription commits before extraction. Processing alternates these steps; completed chunks are never retranscribed because a later summary window failed. Each source is at most 6,000 characters and an extraction window at most 10,000 source characters.
- Prior context is limited to 64 topics/entries and 6,000 entry characters, ranked against the new window so an old relevant decision remains retrievable. Lexical retrieval has no semantic-recall guarantee.
- Structured output is capped at 7,000 tokens and response bodies at two million characters. HTTP connect/read/call timeouts are 30/90/150 seconds. Coroutine cancellation cancels the call through body reading and closes the response.
- Workers yield after six minutes by appending a successor, leaving time for the final bounded call within WorkManager's normal execution window. IO/408/429/selected 5xx failures back off, with four retries per request. Invalid/permanent errors keep all committed content.

## Storage and validation

Normalized tables store transcript segments, source coverage, topics, entries, revisions/evidence joins, chapters/source joins, notes state, audio chunks, jobs and snapshot metadata. Canonical transcript text is not copied into every summary. Explicit processing moves a legacy transcript into this store while retaining its previous notes/map.

Typed extraction returns topics, entries with exact quotes, allowed exclusion reasons and optional chapter sources. Validation checks IDs, bounds, ordered sources, quote membership, scope, quoted owners/deadlines, known revised-decision references and complete source accounting. Chapters require contiguous, nonoverlapping provider-timed sources. Decisions/actions remain distinct from proposals. These checks establish source linkage, not semantic truth, relevance or full coverage of facts within a segment; the UI offers source review.

Each Room transaction rechecks job cancellation/scope and source revisions, then atomically merges notes, evidence, coverage, cursor and snapshot metadata. Stable keys, exact normalized-text matching and idempotent cursors prevent mechanical duplicates. Paraphrase deduplication still depends on the model. Changed decisions retain history. An unapplied AI candidate cannot supersede another decision.

Manual saves use optimistic revision checks and append history. Summary views and map share those records. Detailed includes revised records; Quick never pads a short meeting. Snapshots record checkpoints/revision metadata; full document-snapshot restoration is not implemented.

Final reconciliation checks that every source is cited or explicitly excluded. It does not send an unbounded full transcript for another model pass, nor treat coverage counts as proof that all important facts were retained. Actual accuracy, organization and completeness require human review of real meeting output.

## Provider and pricing

`openrouter/free` is the default text router, with required structured-output support and no paid text fallback. Free availability/limits can change. The configured Whisper STT model is billed independently; transcription is not entirely free. No real provider call or billing was exercised here. If a server completes a request but its result cannot be committed, retry may be billed again.

The implementation follows OpenRouter's [speech-to-text contract](https://openrouter.ai/docs/guides/overview/multimodal/stt): multipart audio, `verbose_json` and segment timestamp granularity. Its [transcription tutorial](https://openrouter.ai/blog/tutorials/transcription-on-openrouter/) explains STT billing. Timestamp support varies; missing timing remains approximate or unknown. Enter the key only in app Settings, where Android Keystore encrypts it; no key is embedded in source or fixtures.

## Remaining work

Live AudioRecord/MediaCodec capture/transcription, local inference, transcript correction history, Q&A, outside-knowledge expansion, graph edges/viewport, cross-meeting FTS/tasks and encrypted backup remain future scope. Short recording/recovery and persistence checks passed on an API 35 emulator. Physical-device microphone/playback/background qualification, full accessibility review, duration/battery targets and actual model-quality acceptance remain outstanding. See [verification](verification.md) and [performance](performance.md).
