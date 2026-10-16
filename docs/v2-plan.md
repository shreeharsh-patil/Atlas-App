# Meeting Atlas V2: implementation contract

**V3 update, 16 October 2026:** [V3 notes](v3-notes.md) supersedes the planned batch-processing portions below. Schema 4 implements normalized transcript evidence, coverage, durable WorkManager jobs, four shared notes views/topic maps, protected edits/revisions, scoped refresh, chapters, action completion and Markdown export. The user selected OpenRouter-only batch transcription; live processing remains deferred. The slice descriptions below also include future scope and are not a list of delivered features. See [verification](verification.md) for executed checks.

This plan preserves the original specification, the V2 brief and the later choices to use OpenRouter, prefer free text models and delete source audio after successful notes. Work proceeds in vertical slices, with recording reliability taking precedence over new AI features. A slice is release-ready only after its device acceptance checks pass. Compiling code is not evidence of battery efficiency, uninterrupted capture or useful AI output.

## V2 baseline boundary (before V3)

Implemented in this increment: segmented Media3 playback and seeking, speed controls and saved position, recording session/segment journal, safe Room v2→v3 migration, validated segment recovery, three AAC quality presets, timestamped bookmarks with editable labels, device routing detection, optional supported Bluetooth communication routing on Android 12+, storage prediction, capture/processing exclusion, actual amplitude waveform with lifecycle-controlled sampling, contextual recording bar, and development diagnostics. Existing OpenRouter processing remains available.

V3 now implements durable batch scheduling, timestamped transcript/evidence records, chapters, templates/revisions, a shared topic-tree map, action completion and Markdown export. Remaining scope includes local inference, transcript corrections, Q&A, global FTS, graph edges/viewport/outside expansion, cross-meeting task workflow, audio import and encrypted backup/restore. Existing AI JSON remains a compatibility read path. Do not expose placeholder controls for undelivered features.

## Architecture decisions

- Keep one Gradle module until there are tested feature boundaries. Packages separate `recording`, `playback`, `data`, `ai`, `ui` and `di`. Extract modules after the transcript contracts stabilize; avoid moving code solely to imitate a diagram.
- The microphone foreground service owns capture. Commands and recorder callbacks serialize on its Main looper. Room operations suspend and file inspection runs on IO. No Activity reference is retained. The application scope handles recovery work that must outlive an Activity.
- Room is the catalog and content authority. Audio stays in `files/recordings/<meeting-id>/`. Cache is only for disposable processing chunks. Mark a change saved only after its local transaction succeeds.
- `AudioWorkCoordinator` atomically grants recording, recovery or processing ownership in this process. This prevents inference and recording from overlapping. The later worker pipeline must use the same coordinator and must not declare a second process. A worker that cannot acquire ownership defers; it must not spin or interrupt capture.
- Media3 owns playback, audio focus, headset disconnect handling and a standard media session/notification. Separate M4A files form a playlist and a continuous logical timeline. Starting capture pauses playback. Never concatenate M4A byte streams.
- DataStore holds recording preferences. The OpenRouter secret remains encrypted with Android Keystore; never bundle it in an APK or log it. Cloud content is uploaded only after the existing disclosure and user action.
- Use the user's OpenRouter key directly for the current local app. Do not introduce accounts or a backend for their own sake. If a managed provider credential is introduced later, keep it on a small Ktor backend; use temporary objects and operational job data only. No permanent server meeting history is required.

## Slice 1 — Record → Save → Reopen → Playback

Implemented behavior:

1. Create a local meeting and request microphone permission from a visible Activity.
2. Promote a correctly declared microphone foreground service before preparing capture. Return `START_NOT_STICKY`; never restart microphone capture from boot or a hidden retry.
3. Probe AAC capabilities, verify with actual recorder preparation, and fall back to supported sample rates. Store the effective configuration and report if file rollover is unavailable. Platform MediaRecorder chooses the actual encoder; do not claim hardware acceleration without measurement.
4. Journal every allocated segment before passing its path to MediaRecorder. File-size rotation targets approximately 20 minutes at the selected bitrate; it is not an exact time promise.
5. Freeze the monotonic clock when paused or stopped. Do not count finalization time. Persist transitions rather than writing the timer every second.
6. Inspect finalized containers and an encoded sample. Construct the playback timeline only from validated positive-duration segments. Keep invalid/incomplete files for explicit deletion; a corrupt tail must not hide a healthy earlier segment.
7. Save local segment metadata and terminal meeting status before publishing Saved. Recovery retries safely using stable segment IDs.
8. Open playback offline, seek across boundaries, change speed, retain position and continue through Media3 controls after leaving the screen.

Acceptance: service integration test, Room migrations from v1 and v2, child preservation on meeting update, close/reopen persistence, cross-segment seek tests, repeated stop/pause commands, corrupt-tail recovery, missing audio/player failure, microphone denial and Activity recreation. Tests are defined in the project; see `verification.md` for actual execution status.

## Slice 2 — Long capture and device qualification

Implemented foundation: session events and integrity diagnostics, storage checks every 30 seconds with approximate remaining time, safe low-storage stop, optional Bluetooth communication route, device input changes, Android 10+ silenced-input pause, and no amplitude polling without a visible waveform subscriber. No application wake lock is held.

Remaining qualification: physical-device lock-screen sessions; phone-call and competing-recorder interruptions; route removal, SCO/BLE hardware behavior and manufacturer power policies; rollover at multiple thresholds; low storage and full disk during finalization; process kill and force-stop; reboot recovery; duration matrix and matched idle-baseline profiling. Earlier Android versions retain system microphone routing rather than attempting unreliable legacy SCO forcing. A failed communication route visibly falls back to system input. Never auto-resume a silenced microphone without user action.

Gate: finish device qualification before describing the recording engine as production-ready or enabling heavy local inference. Do not disable Android restrictions, request unrestricted battery exemptions by default, or promise survival after force-stop/reboot/permission revocation.

## Slice 3 — Durable transcript and scheduler

Add a migration with `ProcessingJob`, `TranscriptSegment`, `TranscriptRevision` and an FTS table. Reuse the original transcript as canonical evidence instead of copying it into every entity.

- `TranscriptSegment(id, meetingId, startMs?, endMs?, timingPrecision, speakerLabel?, text, confidence?, revision, order)`; `timingPrecision` distinguishes provider timestamps from a coarse chunk interval. Missing word/segment timing must remain missing, never synthesized from word counts.
- Corrections append a revision with old/new text and parent revision; preserve original provider output as evidence and invalidate affected generated citations. The source is still readable offline.
- `ProcessingJob(id, meetingId, stage, mode, constraints, status, progress, attempt, sourceRevision, idempotencyKey, lastErrorCode, updatedAt)` with a unique idempotency key. Store chunk checkpoint metadata, not duplicated full transcripts.
- **Process now** enqueues unique work and begins when allowed. **Process later** uses WorkManager network/charging/battery constraints, persisting the user's cloud authorization and selected model/config snapshot. Respect expedited quotas; do not misuse a microphone or mediaPlayback service to run AI.
- **Local processing** appears only when a compatible installed runtime/model is verified. Define a `TranscriptionProvider` contract shared by local and remote implementations. Display model size, estimated memory and supported language; absence is a capability state, not a fake download button.
- One inference at a time. Defer heavy work while recording; for local models, offer charging-only, monitor Android thermal status and pause at severe thermal levels. Persist completed chunks and cancel safely at chunk boundaries. Cancellation must preserve input files and already committed transcript segments.
- Store the transcript immediately after each validated chunk, independently of summary success. Network retries use bounded exponential backoff and explicit rate-limit handling. Never retranscribe completed chunks merely because summary generation failed.
- Use bounded streaming uploads. No full-audio RAM buffer. Resolve chunk durations and offsets from source media, preserving timestamp provenance.

Acceptance: offline local transcript search, cancelled/failed/retried job, duplicate enqueue, app restart, network changes, invalid provider response, recording starts while work is queued, source revision mismatch and thermal/charging transitions.

## Slice 4 — Grounded summary, chapters and tasks

Use one typed contract for local and cloud engines. A source reference is `(transcriptSegmentId, transcriptRevision, startChar?, endChar?)`; timestamps are resolved from the source row. Require nonempty, existing references for every factual item. Validate schema, string/collection limits, unique IDs, citation membership and time bounds before committing. Invalid output yields a retryable error and leaves audio, transcript and prior notes untouched.

Typed result:

```kotlin
data class EvidenceItem(val text: String, val sources: List<SourceRef>)
data class ExtractedAction(val task: EvidenceItem, val assignee: String?, val deadline: String?)
data class Chapter(val title: String, val startMs: Long, val endMs: Long,
                   val bullets: List<EvidenceItem>, val sourceIds: List<String>)
data class MeetingNotes(val overview: List<EvidenceItem>, val keyPoints: List<EvidenceItem>,
                        val confirmedDecisions: List<EvidenceItem>, val proposals: List<EvidenceItem>,
                        val openQuestions: List<EvidenceItem>, val actions: List<ExtractedAction>,
                        val chapters: List<Chapter>)
```

- Quick Brief aims for 5–8 useful bullets for substantial meetings, fewer for short or sparse sources. Standard groups overview, key points, confirmed decisions and actions. Detailed adds chapter notes and citations. Do not pad output to satisfy an arbitrary count.
- General, lecture, technical, standup, interview, brainstorming and project-planning templates change extraction focus and section ordering only. Missing owners, dates, decisions or lecture conclusions stay absent.
- Hierarchical summarization must carry original segment IDs through condensation. The final model receives cited intermediate items and can only cite their original source sets. Do not summarize uncited summaries and then claim source grounding.
- Generate chapters only where topic changes and trustworthy time ranges are supported. No invented chapter divisions. Persist title, interval, bullets, transcript links and related node IDs, and seek/jump using the same playback/transcript timeline. If retention deleted audio, chapter navigation opens the cited transcript.
- Normalize `Summary`, `SummaryEntry`, `SummaryRevision`, `ActionItem`, `Chapter` and `ChapterSource` tables. Generate a new candidate version; never overwrite a user-edited version silently. Manual edits append revisions. Record human edits as human provenance, retaining original citations for review.
- Actions have task, optional established assignee/deadline, source links, open/done status and edit history. Decisions remain separate from proposals and unresolved questions. The Tasks destination filters outstanding actions across meetings. No external reminders/calendar writes without explicit approval.
- Commit validated notes/actions/chapters in one transaction. Only then apply the user's explicit audio-retention preference. Preserve the earlier default of deleting audio after notes as requested; make retaining playback a visible option. A cleanup failure can be retried without repeating AI requests.

## Slice 5 — Persistent graph and expansion

Migrate existing JSON into relational nodes and edges without regenerating old maps. Assign deterministic IDs within a stored migration/generation revision. Persist every branch locally.

- `MindMapNode`: meeting, parent, title, body, provenance (`MEETING`, `OUTSIDE_KNOWLEDGE`, `USER`), sources, expansion state, user-edited flag, generation revision and timestamps.
- `MindMapEdge`: source, target, relationship type and optional evidence. Hierarchy is acyclic; relationship links need not imply parentage.
- `MindMapExpansion`: node, instruction, evidence mode, source revision, idempotency key, status and result node IDs. A unique request key plus transactional insertion prevents duplicates after retries.
- `GraphViewport`: meeting/map revision, pan, zoom and collapsed IDs. Store on gesture end/navigation; don't write every frame.
- **From Meeting** can only use cited transcript content. **Learn More** produces separately marked outside knowledge, never relabelled as meeting fact. Expansion history and provenance survive restart.
- Create a node from a selected summary item, transcript range or saved answer with its existing sources/provenance. Manual node edits append history and survive regeneration.
- Compute a stable hierarchical layout off Main using stable sibling order and cached measurements. Render only visible nodes/edges, expand incrementally, respect reduced motion, and open contextual actions/details in a native bottom sheet. Return to the same camera position without an API request.
- Related-meeting links remain cross-record references; do not merge or rewrite source transcripts.

Acceptance: offline reopen with all branches/camera state, overlapping retries, cancellation, duplicate labels with different sources, large/deep graphs, edited-node preservation, invalid parent/edge/source references and explicit outside-knowledge markings.

## Slice 6 — Cited Q&A and cross-meeting retrieval

Retrieve local transcript segments first, constrained to the selected meeting/node. Use FTS and bounded excerpts initially; embeddings are optional only after retrieval evaluation shows a need. Send source IDs with excerpts, require citations in the typed answer and validate them. If the evidence cannot answer the question, say so. Separate outside knowledge when explicitly requested. Save questions, answers and source references locally, support answer-to-node conversion, and reopen history offline. Do not silently upload unrelated meetings.

FTS indexes meeting titles, canonical transcript text, summary entries, decisions, tasks and node content. Use a document-index table linked by record type/ID and transactional updates/triggers; FTS is a rebuildable index, not a second evidence store. Handle punctuation safely in user MATCH queries. Page results and transcripts.

## Slice 7 — Product finish, export, backup and release

- Primary navigation becomes Meetings / Search / Tasks / Settings when those destinations are functional. Detail uses Overview / Transcript / Mind Map / Ask. Use a rail and two-pane layouts on tablets; phone controls remain comfortable at large font sizes. Keep the restrained neutral/evergreen identity and minimal shadows.
- Use lifecycle-aware flows, lazy local loading and skeletons only for real outstanding work. Screen readers get status announcements, labelled controls, logical focus, selectable transcripts and accessible source links. Verify TalkBack, 200% fonts, contrast, reduced motion and wide/short screens.
- Import through the Storage Access Framework and validate MIME/container/duration without loading files into RAM. Copy durable input into managed storage before claiming import success.
- Export Markdown/JSON and transcript with timestamps; export segmented audio with a manifest or correctly remux it. Share only through FileProvider/SAF, not globally readable files.
- Optional encrypted backup: user-selected destination, versioned manifest, file inventory/hashes, authenticated encryption and a deliberate passphrase/portable recovery-key design. Device-only Keystore ciphertext is not a portable backup. Never export provider keys. Stage a restore in a temporary directory, enforce path/size/record limits, authenticate/check hashes and references, then transactionally adopt it. A failed restore must leave the current catalog intact. Test wrong passwords, tampering, interruption, old versions and missing media.
- Storage management shows real file/database sizes and explicit choices to delete source audio, generated content or a whole meeting. Use confirmation for destructive actions and refuse deleting an active capture. No automatic retention beyond the chosen policy. Audio encryption at rest remains separate work; internal storage and Room are not encryption claims.
- Qualify all performance goals in `performance.md`; do not invent measurements. No cloud sync, accounts, teams, collaboration, analytics dashboard or optional web app before these gates pass.

## API references used

- [Microphone foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [MediaRecorder API and file rotation](https://developer.android.com/reference/android/media/MediaRecorder)
- [Supported communication routing](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))
- [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)
- [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test)

Keep framework behavior and dependency versions verified against these official references when advancing a slice.
