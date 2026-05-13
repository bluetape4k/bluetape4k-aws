# README Hero And Architecture Refresh

## Context

The root README needed the same visual entrypoint quality as the leader project
and a clearer statement of the AWS repository purpose.

## Decision

Store the generated AWS workbench image in `docs/assets/aws-workbench.png` and
reference it from both README locales. Keep the existing Mermaid architecture
diagrams and add explicit purpose and feature sections ahead of the module list.

## Outcome

The README entrypoint now highlights the coroutine, Spring Boot, Ktor, and AWS
service integration story before readers reach installation details.

## Verification

- Confirmed the generated asset exists as a PNG under `docs/assets`.
- Verified both README locales reference the shared image path.

## Future Guidance

When adding repository-level visual assets, keep them in `docs/assets` and update
all existing README locales in the same PR.
