# Contributing

## Language

Write documentation, UI text, source comments, issues, PRs, and commit messages in English.

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

For failures involving the vehicle, record the app/Android version, adapter, protocol, PIDs, and reproduction steps, with personal identifiers removed.
Never document an unverified capability as supported.

## Labels and priorities

- Type: `type:bug`, `type:feature`, `type:task`, `type:research`.
- Area: `area:android`, `area:bluetooth`, `area:obd`, `area:data`, `area:ui`, `area:sync`, `area:docs`.
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

For Android or telemetry changes, use the Gradle wrapper from the repository root:

```powershell
.\gradlew.bat :core:telemetry:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On macOS or Linux, use `./gradlew` with the same tasks.
See [Development](docs/development.md) for the JDK, SDK, installation, and simulator setup.
Report the commands actually run and their results; compilation is not evidence of installation on the A53.

The current simulator checks deterministic data, timeout, and missing-data behavior.
Add parser, persistence, migration, and synchronization coverage when those features are implemented, including fragmented OBD responses.
Do not add tests that merely repeat the implementation.

A task is done when it meets its criteria, preserves existing history, documents its behavior, has sufficient verification, and its changes are merged.
Changes that depend on the vehicle must explicitly state if they were tested only with a simulator.
The user reported a successful simulator test on October 10, 2026 and confirmed the Samsung A53 runs Android 16. The RX8-002 foundation is complete and PR #12 is merged. Bluetooth pairing has not been tried; RX8-001 and RX8-003 hardware validation remain pending. Use `Refs #N` while any criteria remain pending.
Actual adapter and ECU compatibility is tracked separately in RX8-001.

## Decisions and releases

Record important decisions, including context, alternatives, and consequences, in `docs/decisions/` as they arise.
Use `0.x.y` versions during development and release notes covering changes, verified compatibility, and limitations.
Do not publish an APK release until there is a real build and installation testing.
