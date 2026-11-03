---
description: Refresh HANDOVER.md so the next session (or Om's Claude) can pick up exactly where we stopped
---

Update `HANDOVER.md` to reflect the true current state of the project. Do this carefully; a stale handover costs the next person an hour.

1. Read `HANDOVER.md`, `docs/PHONE_TEST_PLAN.md`, `docs/DECISIONS.md`, and `git log --oneline -25` plus `git status`.
2. Find out what changed since the "Last updated" line: `git log --since` or compare with the session log's last entry. Read the diffs of anything that touches behaviour.
3. Run `./gradlew :core:test` (with `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`) and, if the app changed, `./gradlew :app:assembleDebug :app:lintDebug`. Record the REAL results (test count, scorecard numbers from `core/build/reports/kavach-eval/scorecard.md`). Never write a result you did not just see.
4. Update the **Snapshot** table, the **Status by component** table (move items between ⬜ / 🟡 / ✅; only mark ✅ for things verified, and only mark an on-device behaviour verified if a row in PHONE_TEST_PLAN.md was actually ticked with a result), **What is verified vs not**, **Next actions** (remove done items, add new ones with a concrete "done when"), **Known risks**, and **Open decisions**.
5. If behaviour deviates from the spec, add a row to `docs/DECISIONS.md`.
6. Append ONE entry at the top of **Session log**: date, who, what was done, what is still open, and the exact next step. Keep it factual and short.
7. Commit with a message like `Update handover: <one line>`. Do NOT push unless the user asked you to.

Be honest about what is unverified. If you could not run something, say so in the file.
