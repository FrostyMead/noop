# Algorithm improvement review

Reviewed 2026-09-07. Scope: on-device WHOOP analytics, with Android as the user's target.

The objective is better measurements and useful trends. Agreement with another wearable is a separate
benchmark: its score is a model output, not a physiological ground truth. The published vendor pages
describe inputs, not sufficient formulas to reproduce their proprietary scores.

## Current implementation and priorities

| Priority | Current evidence in the repository | Proposed improvement and validation |
| --- | --- | --- |
| 1. Resting HR artifacts | `RecoveryScorer.restingHR` had bin-quality guards, but its fallback could return rejected dropouts. `SleepStager.sessionRestingHR`, used by the daily pipeline, duplicated the older unguarded estimator. | Implemented: share the guarded estimator; exclude samples below its existing 25 bpm floor before averaging or counting. Dropout-only input returns missing. Verify clean and corrupted streams at several HR levels, sparse windows, and sleep-session wiring. |
| 2. Overnight HRV quality | Gap-aware RR cleaning already exists. `sessionHrvWindows` still permits RMSSD from very few usable beats; the recovery headline averages window RMSSD. | Evaluate a quality-aware overnight aggregation using valid duration, retained RR pairs, artifact fraction, and coverage across the night. Compare against ECG/NN references where available and same-day WHOOP exports. Avoid choosing sleep stages solely to force a numeric match. |
| 3. Resting HR definition | The daily headline takes the minimum session RHR, so a nap can beat the main night. `PrimarySessionRestingHR` and shadow collection already support an overnight-mean candidate. | Extend the existing comparison to more wearers and held-out nights, including irregular sampling. Compare overnight mean, time-weighted mean, and current floor before changing recovery/strain baselines. No default switch is justified by the existing small study alone. |
| 4. Confidence | Effort confidence is based on a 3,600-sample count. Counts mean different durations at 1 Hz and 30-second cadence, and do not expose long gaps. | Evaluate observed-time coverage, cadence, and dropout distribution. Keep incomplete days distinct from genuinely low-load days. Do not fill missing periods with invented HR. |
| 5. Readiness history | Recovery uses HRV/RHR/respiration, current Rest, and temperature. Readiness separately uses HRV/RHR/respiration and load. Sleep debt and training-load helpers already exist. | Evaluate recent sleep history and recovery demand as separate, explainable drivers. Measure the benefit of each addition individually; avoid counting the same sleep or HRV signal repeatedly. |
| 6. Sleep | V1/V2 classifiers and sparse-motion confidence guards exist; stages remain estimates. | Prioritize sleep/wake timing, wake-after-sleep-onset, and nap handling. Test stage changes against PSG epochs through the existing sleep benchmark tools; report per-stage agreement and confusion matrices, not only total minutes. |

## What the vendors document

- [WHOOP Recovery](https://support.whoop.com/s/article/WHOOP-Recovery?language=en_US)
  uses personal physiological signals including HRV, resting HR, sleep performance, and skin temperature.
  NOOP already has corresponding inputs where the strap data supports them; arbitrary weight changes
  would not establish agreement or accuracy.
- [Garmin training readiness](https://www8.garmin.com/manuals/webhelp/GUID-BC69665A-98C5-4953-BD19-F5EB5A26A4D0/EN-US/GUID-C21BE0C8-A08E-4DA1-B6C6-2E0E2DDDB372.html)
  combines last night's sleep score, recovery time, HRV status, acute load, three-night sleep history,
  and three-day stress history, updating during the day.
- [Fitbit / Google Health readiness](https://support.google.com/googlehealth/answer/14236710?hl=en)
  uses HRV, RHR, and recent sleep, including the past week's sleep patterns. It requires seven nights
  for its initial personal baseline. [Fitbit Air](https://store.google.com/product/google_fitbit_air?hl=en-US)
  exposes readiness and cardio load through this ecosystem.

## Evaluation before broader scoring changes

Use consented local recordings and existing fixtures; no upload or account service is needed. Record
strap family, sampling cadence, missing coverage, timezone, sleep boundaries, and reference source.
Match RMSSD with RMSSD and equivalent time windows; do not compare SDNN with RMSSD. Keep vendor
score agreement separate from validation of HR/RR/sleep measurements.

Prespecify candidate settings and split by participant and time before fitting. Use held-out nights and
workouts spanning low/high HRV, rest days, hard sessions, fragmented sleep, naps, and dropouts. Report
sample counts, missing-result rate, mean absolute error, signed bias, and agreement spread. Correlation
alone can hide a large systematic offset. Compare each candidate against the unchanged incumbent on
the same usable recordings, including uncertainty and coverage groups.

Existing work to reuse: `docs/RR-OPTIMIZATION.md`, analytics agreement tests, primary-session RHR shadow
metrics, and `Tools/SleepPSG`. The RR review documents why an eight-night fitted recovery model
overfit and why stage-selected HRV did not solve absolute agreement on that dataset.

## Change boundaries

This change hardens the existing resting-HR definition on Kotlin and Swift; it does not introduce a new
physiological estimator or fit vendor scoring constants. Dense valid bins retain their existing result.
A sparse low bin can no longer override a well-populated valid bin in the sleep-session path. Entirely
invalid windows now stay missing; plausible sparse fallback remains available and is still an estimate.
Because session RHR also helps confirm daytime sleep, artifact-driven daytime sessions may be rejected.
Previously stored results are not migrated; new analysis uses the corrected helper, and imported
non-null resting-HR values remain owned by their existing source policy.

Synthetic regression tests establish behavior under injected dropouts, not real-world vendor-level
accuracy. Broader model changes still require the paired recordings and validation above.

## Local verification

The added Kotlin regressions reproduced three failures before the estimator fix. After the fix and
sleep-session wiring, 202 tests across 27 selected recovery, analytics-engine, baseline, and sleep-stager
suites passed with no failures, errors, or skips. Android application and test Kotlin compilation passed.
Matching Swift tests were added, but could not be run on this Windows host without a Swift toolchain
and the package's supported macOS environment. No paired wearable or PSG recording was evaluated in
this change, and no strap behavior was tested.
