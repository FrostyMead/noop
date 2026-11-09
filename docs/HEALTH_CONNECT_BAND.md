# Any band via Health Connect — design

**Status:** Phases 0 and 1 built on `feature/health-connect-band`, awaiting a check on a real band's data.
Band scoring is behind an experimental switch, off by default. Android only; on Apple, the HealthKit
Apple Watch (`SourceKind.liveAppleWatch`) already plays this role.

**How to use it today:** Data Sources → Health Connect → *Check what your band sends* → *Add as a device*
on the band's app. Its heart rate and sleep then sync on every app open (or *Sync bands now*). *Score days
from my band* (experimental) lets it own the days no strap covers.

## Goal

Let almost any fitness band or watch act as a NOOP device without a per-brand Bluetooth driver. On
Android, nearly every band's own phone app (Garmin Connect, Zepp/Amazfit, Mi Fitness, Samsung Health,
Fitbit, Oura, Withings, Polar Flow, …) writes to **Health Connect**, which is on-device. NOOP reads it
there. No vendor cloud, no account, no network: the scope limits in `CLAUDE.md` are unchanged.

## What exists today

`HealthConnectImporter` already reads steps, calories, HR, resting HR, HRV (RMSSD), sleep with stages,
SpO2, respiration, VO2max, body composition, workouts, distance and hydration. It is an **import**:
manual, or on app open when "auto-sync" is on. It also **summarises**:

- HR is reduced to a per-day average; no stream is kept.
- Sleep becomes per-night stage totals, filed under the WHOOP id (`my-whoop`) on strap-free days.
- An import-only day gets a Recovery (Charge) from daily HRV + resting HR (`WatchRecovery`), but
  **no Effort**, because Effort needs an HR stream.

## Target

A **Health Connect band** is a registered device (`pairedDevice` row) whose id is `hc-<package>`, with
`<package>` being the band app that writes the data. It:

1. **Keeps the band's HR stream** as `HrRow` samples under its own id, so the existing day-owner
   resolver (`RegistryDayOwnerSource`, invariant I2) can pick it for a day and `analyzeRecent` scores
   Effort from it. `StrainScorer` already credits each reading with up to `maxSampleGapMin` (2 min), so a
   band writing HR at least every 2 minutes scores without under-counting.
2. **Keeps sleep as sessions with a stage timeline** under its own id. A non-WHOOP owner without
   gravity already hands its own stored sessions to `analyzeDay` as `providedSleep` (the Oura path,
   #804), so the Sleep tab and Rest work.
3. **Recovery** comes from the band's HRV (RMSSD) + resting HR. Health Connect carries summary HRV, not
   RR intervals, so this uses the summary path, never an RR-derived figure the data cannot support.
4. **Syncs on its own**: incremental reads via Health Connect change tokens. Background reads need
   `READ_HEALTH_DATA_IN_BACKGROUND` where the platform offers it; otherwise sync on app open, as today.

## Phases

| Phase | What | Validates |
|---|---|---|
| **0. Inspector** | Data Sources → "Check what your band sends": per source app, the record types, HR cadence (typical gap between readings), nights with stages, HRV / resting HR presence over the last 7 days. Read-only, nothing stored. | Whether a real band's Health Connect data can carry Effort / Sleep / Recovery **before** anything is built on it. |
| 1. Band device + capture | **Built.** *Add as a device* in the inspector registers `hc-<package>` (`SourceKind.healthConnect`, never active, never a BLE source); stores plausible HR samples and sleep sessions with a stage timeline under that id; syncs on app open and on demand, re-reading a 2-day overlap. No migration (`sourceKind` is free text). Also offered as **Any band or watch** in the Add Device wizard (the shared `HealthConnectBandFlow`). | The data lands, isolated by device id, without touching WHOOP rows. |
| 2. Scoring | **Partly built, experimental, off by default.** With the switch on, the band is a day-owner candidate at priority 1 (below the active strap, like the HealthKit watch), so Effort comes from its HR and Sleep from its stage timeline through the existing paths. Recovery: a day the band owned but could not score from RR (it has none) takes its Charge from the band's own daily HRV (RMSSD) + resting HR against the band's own history, through the same `watchRecoveries` engine import-only days use (calibrating for its first 7 nights). This runs as a post-pass on the persisted scores, keyed by their Effort provenance, so the near-64KB `analyzeRecentOnCpu` is byte-identical (#1524). | Scores **track** a varying input across nights, not one matched night (`CLAUDE.md`, "validate against the artifact"). |
| 3. Staying current | **Built (foreground).** Bands sync on app open and every time NOOP returns to the foreground, at most every 15 min. **Not built:** a true background worker. It needs Health Connect's sensitive `READ_HEALTH_DATA_IN_BACKGROUND` permission, which the app has deliberately not requested so far (see the note beside `syncHealthConnectIfStale`); adding it is a maintainer decision. | Today stays current without opening the app. |

## Rules carried over

- WHOOP never regresses: a band never outranks the active strap for a day, and a band's rows never
  land under a WHOOP id.
- No new physiological estimator from thin evidence: HRV stays what the band reported (RMSSD), never
  relabelled as SDNN, never reconstructed into RR.
- Imported values stay distinguishable from NOOP's computed ones (source badges, as today).
