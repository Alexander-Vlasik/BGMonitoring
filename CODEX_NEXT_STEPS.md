# BGMonitoring – Where we are vs. goal (Feb 2026)

## Goal recap
Build a *research* app (Android 11–16, Shizuku-enabled, test devices) that periodically captures system state related to background execution and turns it into **time-series metrics**:
- current **App Standby Bucket** (and transitions over time)
- **JobScheduler quotas** (limits + remaining + consumption) and how they change with bucket
- **Work execution history** (runs, failures, duration, sessions)
- **Device Idle / Doze state** context
- **AppOps / background restrictions** signals (esp. location in background)
- ability to compare 2 scenarios: host app **with** Always Location vs **without**

## Where the project is now (from the ZIP you shared)
✅ Snapshot capture pipeline exists:
- WorkManager worker scheduled periodically
- Shizuku shell wrapper executes commands
- Raw snapshot files written to app storage
- UI can trigger / show basic status

❌ What’s missing for “normal analysis”
- No reliable **parsing/normalization**: raw dumps are not turned into structured numbers.
- No **time-series store**: you can’t query “bucket over last 7 days”, “quota remaining trend”, etc.
- No **derived metrics** (deltas, rates, transitions, “minutes remaining”, success rate).
- No **visualization layer** (charts/tables) – currently it’s mostly “raw text”.
- No clear **schema/versioning** for snapshots → hard to evolve without breaking analysis.

This is why it feels “not what you wanted”: you built the **logger**, but not the **analytics** yet.

## Immediate priority (small, high-impact)
### 1) Produce a *Summary JSON* per snapshot
Add a `SnapshotAnalyzer` that reads the raw files and outputs:
`summary_<timestamp>.json` next to the raw dumps.

**Schema v1 (example)**
- `meta`: timestamp (wall-clock), elapsedRealtime, device build, sdk, package, uid
- `standby`: bucketRaw (int), bucketName (ACTIVE/...), source (cmd/api)
- `jobscheduler`:
  - `quotaController`: for each bucket → allowedTimePerPeriodMs, windowSizeMs, jobCountLimit, sessionCountLimit
  - `currentBucketQuota`: remainingMs, executionTimeInWindowMs, executionTimeInMaxPeriodMs, jobCountInWindow, sessionCountInWindow, rateLimit windows
  - `lastRun`: lastExecutionStart/Stop if present in dump
  - `inQuotaAlarms`: parsed count + next alarms (if present)
- `deviceidle`: isDeviceIdle, deepIdle, maintenance windows if present
- `appops`:
  - location ops (foreground/bg modes), denied counts if present
  - other ops of interest (ACTIVITY_RECOGNITION, BODY_SENSORS if used)

### 2) Build time-series “rollups”
From summary JSONs, generate a compact:
- `timeseries.jsonl` (one line per snapshot) OR SQLite/Room table.

At minimum store:
- bucketName
- remainingQuotaMs (for current bucket)
- executionTimeInWindowMs/maxPeriodMs
- job/session counts
- key appops modes (bg location allowed/ignored/denied)
- doze state

### 3) UI: show dynamics, not raw dumps
Minimum screen:
- “Bucket over time” (step chart)
- “Remaining quota over time” (line chart)
- table of bucket transitions (timestamp, from→to, new limits)
- last 24h: number of worker runs, failures, average duration

## Parsing notes (specific to your sample dumps)
- `standby_bucket.txt` currently contains `10` → this usually maps to **ACTIVE** bucket. Don’t rely on magic numbers: map via `UsageStatsManager` constants when available and keep the raw int too.
- `dumpsys jobscheduler <package>` contains both:
  - per-app quota state (remaining, used, counts)
  - bucket configuration (allowed time, window sizes, etc.)
So it is enough to extract the core quota metrics – but only after we parse it into structured fields.

## “Does dumpsys affect the target app?”
In general: **it queries system services** and reads their in-memory state. It should not require the target app process to run.
Cost is mostly:
- binder work inside system_server
- some CPU time to format the dump
This is low compared to running the app’s own background work, but still don’t poll too aggressively (15 min is reasonable).

## Next features (after the core analytics)
- Add `dumpsys batterystats --charged` baseline + periodic `dumpsys batterystats <package>` (careful: big output; parse only key fields)
- Add comparison mode (two packages OR two scenarios) and “A/B view”.
- Export: zip summaries + raw, or upload summaries to server.

## Deliverables for Codex (what to implement next)
1) `SnapshotAnalyzer` (new file) + unit tests with your saved dumps as fixtures.
2) `SummaryWriter` that writes `summary.json` per snapshot.
3) `TimeseriesRepository` (JSONL or Room) to append one row per snapshot.
4) UI screen with 2 charts + transitions table.
5) Minimal “Export” action to share the collected data.

