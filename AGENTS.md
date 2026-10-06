# Project version policy

The user has requested that the current code be preserved as version 1 and
that every future change or commit update the version.

- `v1.0.0` is the immutable Git tag for the exact code before version tracking
  was introduced. Never move, replace, or delete this tag.
- `version.properties` is the source of truth for the project version name
  and integer build code.
- Before editing tracked project files for a new change, run
  `python3 tools/version.py bump`. This increments the patch version and build
  code together and synchronizes Android, iOS, web, and backend metadata.
- Bump once per change set / commit, including documentation-only changes.
  If a task needs multiple commits, each commit needs a new bump. Do not bump
  again for individual file edits within the same uncommitted change set.
- Use `bump --part minor` or `bump --part major` only when the scope warrants it
  or the user requests it. Always increase the integer build code.
- Add a concise entry to `CHANGELOG.md` for each version. Before committing,
  run `python3 tools/version.py check` and the checks appropriate to the change.
- Include all version metadata and the changelog in the same commit as the
  change. Report the resulting version and build code to the user.
- Keep any explicit Android release overrides consistent with the tracked
  version. Never reuse or decrease a build code already uploaded to a store.

The initial version-tracking setup is the one-time exception: it establishes
version 1.0.0 / build code 1 without changing application behavior. The next
change must be version 1.0.1 / build code 2 (or a higher requested version).
