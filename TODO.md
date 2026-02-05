# TODO / Task Description (for Codex)

## Where this file should live
Recommended path in repo: `docs/TODO.md`  
(If you want it at root instead, rename to `TODO.md` or `TASK.md`.)

---

## Project: BGMonitoring

### Goal
Create a **research-only Android app (Android 11–16)** to monitor how Android changes an app’s ability to do **background work** over time (standby buckets, JobScheduler quotas, doze/idle state, app ops).  
This is for **test devices**, not production. Using **Shizuku** to run diagnostics is allowed.

### Why
We observed that an app which is not opened for a few days starts:
- waking up less often,
- doing less background work,
- and hitting background restrictions/quotas.

We want **historical monitoring + graphs** to explain *what changed* and *why*.

### Key comparison scenario
We test two host apps running the same SDK background logic:
1) Host app with **Always Location** permission
2) Host app **without** Always Location permission

Hypothesis: this difference affects bucket/quotas and leads to fewer wakeups in scenario (2).

---

## MVP (v0.1) — Raw snapshots first (NO parsing)

### Sampling
- Collect a snapshot **every 15 minutes**
- Foreground service is allowed during collection
- Optional: request “Ignore battery optimizations” (user-controlled)

### Commands to collect (store raw output, unfiltered)
For a selected `targetPackage`:

1. `dumpsys jobscheduler <targetPackage>`
2. `am get-standby-bucket <targetPackage>`
3. `dumpsys deviceidle`
4. `cmd appops get --uid <targetPackage>`

### Storage format
Each snapshot is a folder on device:

```
files/snapshots/<targetPackage>/<yyyy-MM-dd_HH-mm-ss>/
  meta.txt
  jobscheduler.txt
  standby_bucket.txt
  deviceidle.txt
  appops.txt
```

`meta.txt` must include at least:
- timestamp (local + UTC if easy)
- targetPackage
- device model
- Android version / SDK_INT
- app versionCode/versionName
- whether “ignore battery optimizations” is granted
- whether a foreground service was used for capture

### Important constraints
- Do **not** filter outputs in v0.1
- Do **not** parse or graph yet
- Do **not** add a database yet
- Ensure snapshot collection is reliable and inspectable first

---

## v0.2 — Derived metrics (after v0.1 is stable)

### App standby
- Current bucket + transitions timeline
- Time spent in each bucket

### JobScheduler / Quotas (from QuotaController / CountQuotaTracker)
- Remaining quota (time + counts) per accounting window
- Out-of-quota events (when visible)
- Rate limits (job/session rate limiting)
- “within quota” vs “out of quota” periods

### “Background sessions” (product-friendly)
- background sessions per hour/day
- success/failure/skip counts (with reason if derivable)
- duration p50/p90
- time since last successful background run

### Battery & sensors (later)
- Add battery correlation (idle/charging)
- Add sensor refusal signals if discoverable via AppOps/dumpsys/logcat on test devices

---

## UX / Presentation (minimal)
- Simple screen to pick target package (manual input is fine for v0.1)
- Button: “Collect snapshot now”
- Toggle: “Enable 15-min periodic collection”
- A screen/list to browse snapshot folders and share/export as zip (optional later)

---

## Collaboration rule
Codex should output **full files** (no patch diffs).
User will copy/commit manually.
