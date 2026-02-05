# BGMonitoring — Codex Plan (English)

Goal: build a **test-only** Android app (Android 11–16) to **monitor background execution restrictions over time** for one or more target packages (apps that embed the SDK). We want **time-series** metrics: how the app’s **App Standby Bucket** changes, how **JobScheduler quotas** shrink/expand, how often jobs are **blocked vs executed**, and what system state (Doze/idle, app ops, battery) correlates with the changes. This is for research on test devices (Shizuku/root-like shell access), not production.

Repository status (already implemented)
- `SnapshotWorker` schedules periodic snapshots (WorkManager, ~15 min).
- `SnapshotCapturer` runs shell commands via Shizuku and saves **raw dumps** into a snapshot folder.
- `SnapshotAnalyzer` produces a **simple summary** (currently very limited parsing).
- UI lists snapshots and shows a basic summary.

We need to take what exists and extend it to hit the research goal: **clear dynamics + concrete numbers**, easy to show to Product.

---

## 1) Clarify what a “snapshot” represents
A snapshot is **NOT a time range**. It is a **point-in-time capture** of system state at `timestamp`.
Time-series is produced by collecting snapshots repeatedly (e.g., every 15 min) and plotting values over time.

Important: `dumpsys` and `cmd` calls run in `system_server` / shell and **do not involve the target app’s process** (except indirect effects like slight CPU usage). They should not change JobScheduler quotas by themselves.

---

## 2) Data collection: keep raw dumps, add structured summary
### 2.1 Keep current raw files (good baseline)
Current snapshot artifacts are already useful for the core question:
- `am get-standby-bucket <pkg>` → current bucket (numeric constants; map to names)
- `dumpsys jobscheduler <pkg>` → per-app job listing + quota/constraints blocks
- `dumpsys deviceidle` → Doze/idle state & whitelists
- `dumpsys appops <pkg>` → location/sensors/foreground restrictions modes
- `meta.txt` → timestamp, device info

These **are sufficient to start** building meaningful statistics.

### 2.2 Add optional dumps for richer explanations (low priority / toggle)
Add them behind Settings toggles (to control overhead):
- `dumpsys batterystats --checkin` (or `dumpsys batterystats <pkg>` when feasible)  
  *Goal:* correlate bucket/quota changes with app’s battery impact and background activity.
- `dumpsys alarm <pkg>` (alarms/allow-while-idle usage; can explain wakes)
- `dumpsys activity service <pkg>` / `dumpsys activity processes` (process importance, cached, etc.)
- `dumpsys usagestats` (if accessible) for last-used / usage events context
- `cmd appops get <pkg>` (sometimes more structured than dumpsys)
- (Sensors) try: `dumpsys sensorservice` and/or appops for `BODY_SENSORS`, `ACTIVITY_RECOGNITION`
  *Note:* “sensor denied” is often visible via AppOps mode + app logs; some denials may only appear in logcat.

---

## 3) Parsing & normalization: make the summary robust across Android 11–16
### 3.1 Output format
For each snapshot, produce:
- `summary.json` (machine-readable, stable schema)
- `summary.md` (human-readable, product-friendly)

Keep raw dumps unchanged for debugging.

### 3.2 Schema (v1)
Create a Kotlin data model, e.g. `SnapshotSummary`:

**General**
- `timestampUtc`, `device` (model, androidVersion, build)
- `targetPackage`, `uid` (if present in dumps)

**App Standby**
- `standbyBucketId` (10/20/30/40/45/50)
- `standbyBucketName` (“ACTIVE”, “WORKING_SET”, “FREQUENT”, “RARE”, “RESTRICTED”, “NEVER”)
- `bucketSource` (“UsageStatsManager API” or “am get-standby-bucket”)

**JobScheduler quotas**
Two levels:
1) **Quota policy table** (per bucket): values like
   - `allowedTimePerPeriodMs`, `windowSizeMs`
   - `jobCountLimit`, `sessionCountLimit`
   - any rate-limit windows if present  
   (This comes from the global `QuotaController` section in jobscheduler output.)
2) **Current app quota state** (for the app’s current bucket and other buckets if shown):
   - `executionTimeInWindowMs`, `executionTimeInMaxPeriodMs`
   - `bgJobCountInWindow`, `bgJobCountInMaxPeriod`
   - `sessionCountInWindow`
   - `remainingTimeMs` (if can be derived; see below)
   - `withinQuota` boolean if present

**Job execution/constraints signals**
From per-job lines (for this package):
- counts:
  - `jobsRegistered`
  - `jobsReady`
  - `jobsBlockedWithinQuota` (constraint includes WITHIN_QUOTA but unsatisfied)
  - `jobsBlockedTimingDelay`
  - `jobsBlockedDeviceIdle / Doze`
- Extract per job if possible:
  - jobId, service, “Standby bucket: …”, “Satisfied constraints …”, “Unsatisfied constraints …”
  - “Run time earliest/latest”  
This gives a very practical “why not running now” view.

**Device idle / Doze**
- `deviceIdleMode` (active/idle/motion/waiting/etc.)
- `isDeviceIdle` / `isDozing` if can be inferred
- `whitelistStatus` for the target package (in any whitelist)

**AppOps**
- `locationModes`: COARSE/FINE/BG location related ops (allowed/ignored/foreground)
- `sensorsModes`: BODY_SENSORS, ACTIVITY_RECOGNITION, etc.
- store raw op mode + last access time if present

**Battery (optional)**
- battery level/charging state (if already present in jobscheduler dump)
- batterystats metrics if enabled

### 3.3 Parsing strategy (important)
`dumpsys jobscheduler` output varies by Android version/OEM. Do NOT rely on a single literal line.
Implement parsing using:
- section detection by headers (e.g. “QuotaController:”, “Registered X jobs:”)
- multiple regex patterns per field with fallbacks
- unit parsing (ms, s, m, h) for “-2m33s767ms” style durations

Implement a `JobschedulerParser` with:
- `parseQuotaPolicyTable(text)`: parses the global `QuotaController` per bucket values.
- `parseAppQuotaState(text, pkg)`: parses the app’s quota state lines that include fields like:
  `allowedTimePerPeriodMs=... windowSizeMs=... executionTimeInWindow=...`
- `parseJobs(text)`: parses “Registered N jobs” block; aggregate blocked reasons from constraints.

**Derived fields**
- If `allowedTimePerPeriodMs` and `executionTimeInMaxPeriod` exist, compute:
  `remainingTimeMs = max(0, allowedTimePerPeriodMs - executionTimeInMaxPeriod)`  
  (only when semantics match; document assumptions in code & summary.md)

---

## 4) Storage: build an efficient time-series database (simple first)
### 4.1 Keep snapshots on disk (already done)
Continue storing per-snapshot folder with raw dumps + summary outputs.

### 4.2 Add lightweight index
Create a small local DB (Room) or a single `index.jsonl`:
- one record per snapshot with key summary fields (timestamp, bucket, remaining time, etc.)
This makes UI fast and avoids re-parsing everything on each screen render.

Recommendation:
- Start with `index.jsonl` (append-only) for speed of implementation.
- Later migrate to Room if needed.

---

## 5) UI: show dynamics + concrete numbers (product-friendly)
### 5.1 Screens
1) **Dashboard (per target app)**
   - Current bucket (big)
   - Current “remaining quota time” (big) + job/session count limits
   - Doze state indicator
   - Quick “blocked reasons” counts

2) **Timeline**
   - Chart: Bucket over time (step chart)
   - Chart: Remaining quota time over time (line)
   - Chart: Jobs blocked by reason over time (stacked counts or separate lines)

3) **Snapshot detail**
   - Render `summary.md`
   - Button to open raw dumps

### 5.2 “Quota by bucket” table (requested explicitly)
Show a table computed from the quota policy section:
- ACTIVE: allowedTimePerPeriod, window, job/session caps
- WORKING_SET: …
- FREQUENT: …
- RARE: …
- RESTRICTED: …
- NEVER: …

This is the “in ACTIVE I can run a lot, in RARE only minutes” visualization.

---

## 6) Experiment support: compare two scenarios
We have two target apps:
- App A: has **Always Location** permission.
- App B: does **not** have Always Location.
Observed: App B starts waking less after a few days.

Add “experiment metadata” in app UI/settings:
- label each target app (A/B)
- store permissions snapshot (from `dumpsys package <pkg>` or PackageManager) once per day
- record “last opened time” (if possible from usage stats; otherwise manual user input button “I opened the app now”)

Output comparison views:
- two timelines overlayed (bucket + remaining quota)  
- simple “days to downgrade from ACTIVE → …” metric

---

## 7) Upload/export (optional)
Add an “Export” action:
- zip selected snapshots (raw + summary)
- optionally POST summaries to server endpoint (disabled by default)

---

## 8) Engineering priorities (do these in order)
P0 — Must have (to show dynamics + numbers)
1) Improve `SnapshotAnalyzer` → produce `summary.json` + `summary.md` per snapshot.
2) Implement robust parsing of:
   - standby bucket (map numeric → name)
   - quota policy table per bucket (from jobscheduler)
   - app’s quota state (executionTime + limits)
3) Build a timeline UI from summaries (bucket + remaining time).

P1 — Strongly recommended
4) Add index (jsonl or Room) so UI doesn’t reparse raw files each time.
5) Add blocked-reason aggregation from job constraints.

P2 — Nice to have
6) Optional batterystats + alarm dumps.
7) Sensor restriction visibility improvements (AppOps + sensorservice/log hints).

---

## 9) Notes for Codex on implementation details
- Keep existing classes and extend them; avoid rewriting everything.
- Parsing should be unit-tested: add a small `testdata/` folder with real dumps and write JVM tests for parsers.
- Be careful with OEM differences (Samsung Device Care etc.): parsing must be tolerant.
- Use “best effort” parsing and surface “unknown” when fields are missing.

---

## Expected demo narrative (for Product)
“We take periodic snapshots of the system’s background policy for an app. Over time we see the app move between standby buckets (ACTIVE → FREQUENT → RARE/RESTRICTED). For each bucket, Android defines a time budget and job/session caps; we plot the app’s remaining budget and show when jobs are blocked (by quota, Doze, or timing constraints). We compare two apps (with vs without Always Location) and quantify how quickly the background capability degrades.”

