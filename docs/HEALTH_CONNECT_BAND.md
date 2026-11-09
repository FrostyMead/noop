# Any band via Health Connect — design

**Status:** Phase 0 (source inspector) in progress on `feature/health-connect-band`. Android only; the
Apple twin (HealthKit as a device) is a separate, later piece of work.

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
| 1. Band device + capture | Wizard entry "Any band or watch (via Health Connect)" picks the source app; registers `hc-<package>`; stores HR samples and sleep sessions with stages under that id; incremental sync. A Room migration only if a new column is needed (the registry's `sourceKind` is free text). | The data lands, isolated by device id, without touching WHOOP rows. |
| 2. Scoring | The band owns days it covers (priority below a live strap); Effort from its HR, Sleep from its sessions, Recovery from summary HRV + RHR. | Scores **track** a varying input across nights, not one matched night (`CLAUDE.md`, "validate against the artifact"). |
| 3. Background sync | Change-token sync on a periodic worker where background reads are granted. | Today stays current without opening the app. |

## Rules carried over

- WHOOP never regresses: a band never outranks the active strap for a day, and a band's rows never
  land under a WHOOP id.
- No new physiological estimator from thin evidence: HRV stays what the band reported (RMSSD), never
  relabelled as SDNN, never reconstructed into RR.
- Imported values stay distinguishable from NOOP's computed ones (source badges, as today).
