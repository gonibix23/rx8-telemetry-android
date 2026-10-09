# Agent guidelines

## Context and scope

Read README.md, docs/product.md, docs/architecture.md, and the issue before changing code.
Initial project: European 2004 Mazda RX-8, 231 metric horsepower (CV); Samsung A53; iLC B0BDRHRR22.
Priority: live data and local trip recording. Prepare for an API without deploying a backend.
The repository starts with documentation and tracking; do not invent build instructions or claim tests were run.

## Language

Use English for documentation, source comments, UI text, issues, pull requests, and commit messages unless the user explicitly changes this requirement.

## Implementation

- Keep Bluetooth transport, OBD protocol, persistence, presentation, and synchronization separate.
- Use an OBD command queue with cancellation, timeouts, and bounded retries.
- Discover capabilities and preserve each sample's source, unit, timestamp, and quality.
- A missing or invalid reading is not zero. Indicate stale readings.
- Do not hardcode a protocol, proprietary PID, or sampling rate as supported without evidence.
- Use a permanent UUID for each vehicle; VIN is optional. Do not identify the vehicle by its adapter.
- Save locally before attempting synchronization. Confirm delivery only after an ACK.
- Identify simulator data clearly and keep it separate from real data.
- Do not add actuator control, clearing fault codes, or ECU reprogramming to the current scope.
- Do not commit real VINs, tokens, signing keys, databases, or captures that have not been anonymized.

## Workflow and validation

- Implement one issue per branch and keep PRs focused.
- Update documentation when the scope or contract changes.
- Run relevant checks. Currently: npm ci and npm run lint:docs.
- When introducing Android, add builds, lint, and meaningful tests; do not claim physical validation based on a simulator.
- Clearly state completed tests, pending tests, and limitations.
- Do not mark an issue complete until its criteria are met.
- Preserve other contributors' changes and do not force-push, delete resources, or change visibility on your own initiative.
