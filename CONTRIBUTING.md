# Contributing

## Language

Write documentation, UI text, source comments, issues, PRs, and commit messages in English.

Original project code and documentation use the [MIT License](LICENSE). Preserve its copyright and permission notice when redistributing, and retain third-party license notices.

## Before you start

Read the [scope](docs/product.md) and [architecture](docs/architecture.md).
The initial priority is live data and trips for a European 2004 RX-8 with 231 metric horsepower (CV), using a Samsung A53 running Android 16.

## Issues

Use the bug, feature, task, or hardware validation form.
An issue should describe a concrete outcome and include:

- The problem or need and the expected result.
- Scope and exclusions.
- Verifiable acceptance criteria.
- Dependencies linked to other issues.
- Evidence and a way to verify the result.

For app work use `area:android` and the relevant feature area; for backend work use `area:api`. Keep API work in `api/` and Android work in `app/` or `core/`, with independent checks. Cross-component changes should link one shared contract issue and specify each component's acceptance criteria.

For failures involving the vehicle, record the app/Android version, adapter, protocol, PIDs, and reproduction steps, with personal identifiers removed.
Never document an unverified capability as supported.

## Labels and priorities

- Type: `type:bug`, `type:feature`, `type:task`, `type:research`.
- Area: `area:android`, `area:bluetooth`, `area:obd`, `area:data`, `area:ui`, `area:sync`, `area:api`, `area:docs`.
- Priority: `priority:P0` (data loss or a major blocker), `priority:P1` (required for the MVP), `priority:P2` (improvement or later phase).
- Status: exactly one of `status:triage`, `status:ready`, `status:in-progress`, `status:blocked`, `status:review`.
- Evidence: `needs:hardware` when validation with the actual vehicle/adapter is still required.

Close completed issues. Labels do not replace dependencies: link the blocking issue and explain why it blocks progress.
Status labels are updated manually; no bot is configured.

## Branches and commits

`main` contains integrated work. After the initial setup, use short-lived branches:

- `feat/12-live-dashboard`
- `fix/18-reconnect-timeout`
- `docs/21-api-contract`
- `chore/2-android-bootstrap`

Use descriptive commits: `feat(obd): decode coolant temperature`, `fix(data): preserve interrupted trips`.
Conventional Commits are preferred, but not enforced by automation.
Do not commit keys, signing files, real databases, or vehicle exports.

## Pull requests

Open a small PR for each objective. Keep it in draft while verification is incomplete.
Complete the template: problem, resulting behavior, related issue, validation, limitations, and screenshots when the interface changes.

Use `Closes #N` only when all acceptance criteria are met; use `Refs #N` for partial progress.
Do not close a compatibility issue based on a simulator test.

Before merging:

1. Review the entire diff and check for personal data or unrelated changes.
2. Run the checks relevant to the change.
3. Explain any pending tests or hardware limitations.
4. Request a review if someone else is collaborating. When working alone, record your own review; an approval that the author cannot provide is not required.
5. Squash merge and delete the branch when it is no longer needed.

Documented rules do not imply that GitHub enforces them. The actual protection status is recorded in the [roadmap](docs/roadmap.md).

## Checks and definition of done

For documentation changes, run `npm ci` and `npm run lint:docs`.

For backend changes, run the package checks from `api/` using its own lockfile; see [API development](api/README.md). Never use real Atlas data as test fixtures.

For Android or telemetry changes, use the Gradle wrapper from the repository root:

```powershell
.\gradlew.bat :core:telemetry:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On macOS or Linux, use `./gradlew` with the same tasks.
See [Development](docs/development.md) for the JDK, SDK, installation, and automatic-session setup.
Report the commands actually run and their results; compilation is not evidence of installation on the A53.

Internal synthetic fixtures check deterministic data, timeout, and missing-data behavior, including the real dashboard cards. There is no Simulator destination in production navigation. Dashboard changes need capability, per-ECU, zero/error/stale, unavailable-favorite, preference persistence/failure, and navigation coverage.
Maintain parser, framing, vehicle-identity, and Room persistence coverage, including fragmented Mode 01/VIN responses and database reopen checks. Add migration tests before changing the schema and synchronization tests when implementing that feature.
Keep exported Room schema files under version control. Never use destructive migration fallback to bypass an upgrade, or use real VINs in fixtures and screenshots.
For the lifecycle service, verify continued samples across backgrounding, screen lock, tab changes, and Activity recreation, plus explicit Stop/disconnect, notification denial, permission loss, and cleanup while still bound.
Check that terminal errors release the socket, acquisition jobs, notification, and wake lock without automatic restart. Keep synthetic fixture behavior separate from real foreground-service acquisition. Instrumented fixtures do not establish A53 battery or sustained-session behavior.
Do not add tests that merely repeat the implementation.

A task is done when it meets its criteria, preserves existing history, documents its behavior, has sufficient verification, and its changes are merged.
Changes that depend on the vehicle must explicitly state if they were tested only with a simulator.
The user reported simulator/Bluetooth success on October 10, 2026 and live-reading success on October 11, 2026 on the A53 running Android 16. PRs #12, #13, and #14 are merged. The user reported `ISO 15765-CAN (11 bit, 500 kbaud)` and later confirmed correct readings in 0.5.0-dev. Do not infer individual sensor support, ECU identifiers, measured rates, or sustained lifecycle behavior from these reports. RX8-001/RX8-004 and remaining hardware checks stay open; RX8-005 adds profile/VIN software with device validation pending. Use `Refs #N` while any criteria remain pending.
Actual adapter and ECU compatibility is tracked separately in RX8-001. Issue #16 tracks the 0.5.1-dev background-session/screen-awake fix; the old-version disconnection report is not evidence that Android removed the Bluetooth bond. The user subsequently confirmed 0.5.1-dev worked and PR #17 is merged. This broadly confirms the fix; measured durations, battery use, detailed notification scenarios, and bond-entry checks remain unreported. RX8-006 dashboard layout and favorite persistence still need A53 validation.

## Decisions and releases

Record important decisions, including context, alternatives, and consequences, in `docs/decisions/` as they arise.
Use `0.x.y` versions during development and release notes covering changes, verified compatibility, and limitations.
Do not publish an APK release until there is a real build and installation testing.
