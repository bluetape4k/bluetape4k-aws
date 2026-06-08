# Issue 292 0.4.0 release documentation

## Context

The 0.4.0 milestone feature issues were complete, but release-facing docs still
needed a final pass before publish.

## Decision

Keep the milestone open until the publish workflow closes it, but create a
0.4.0 documentation issue so README, WIP, CHANGELOG, and diagram evidence are
tracked before release.

## Outcome

- Root README installation snippets now use `0.4.0`.
- `WIP.md` now describes release documentation and publish preflight instead of
  the old post-0.3.1 development queue.
- `CHANGELOG.md` now has a 0.4.0 summary covering DAX, CloudWatch/Logs, IMDS,
  S3 Access Grants, S3 Vectors, Micrometer, Floci-first emulator migration,
  Ktor ecosystem reuse, and CI/Nightly hardening.
- `aws-kotlin` module README files use the correct
  `io.github.bluetape4k.aws` group in dependency examples.

## Verification

- `python3 tools/generate-root-readme-diagrams.py` passed and regenerated no
  tracked diagram changes.
- Targeted README scans checked stale `0.3.1`, stale AWS group coordinates,
  and 0.4.0 feature keywords.
- `git diff --check` passed.

## Future Guidance

For release-prep docs issues, update `WIP.md` after feature work closes so it
does not keep advertising a completed active backlog. Keep release milestone
closure in the publish workflow, not in the documentation cleanup PR.
