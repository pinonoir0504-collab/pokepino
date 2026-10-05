# ポケピーノ

Android build repository for Pokepino.


## v1.0.0

- Combines the original 1,410-record catalog with a generated historical reference catalog.
- Stores compact offline visual signatures instead of bundling source reference photos.
- Uses conservative two-stage recognition: Pokemon species model plus figure-level visual matching.
- Groups visually indistinguishable reissues and asks the user to choose the release instead of guessing.
- Validates catalog integrity before every APK build.

- Reference catalog currently contains 1,541 concrete/reference entries covering 889 Pokemon species with offline visual signatures.


## v1.0.1

- Runtime validation now includes API 35 emulator UI tests, camera FileProvider checks, and on-device reference matching.
- Real Pokemon Kids visual signatures are the primary recognition path; the generic Pokemon classifier is now fallback-only.
- Validation on 180 real references / 540 transformed queries measured 89.1% species top-1, 94.4% visual-group top-5, and 100% precision on high-confidence auto-registration cases in this test set.
- Added stable UI semantics used to verify navigation and collection updates automatically.
