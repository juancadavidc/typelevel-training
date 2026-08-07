---
name: write_progress_report
description: Write a short, high-level progress update for the team (Avances / Próximo objetivo / Bloqueos / Necesito ayuda) into `docs/progress/<YYYY-MM-DD>.md`, deriving the content from git activity since the previous report. Use whenever the user asks for a progress report, an update for the team, a "reporte de avances", "cómo voy", a status update, a daily/weekly summary to send to their lead, or says something like "escribe el avance de hoy" or "arma el mensaje para el equipo". Trigger it even when the user does not name the file or the folder — if they want to tell someone how the work is going, this is the skill.
---

# write_progress_report

Produce a **short message the user will paste into Slack/Teams** for their team, and persist it as
`docs/progress/<YYYY-MM-DD>.md`.

The audience is a lead or teammates who are not reading the code. They want to know, in a few
seconds: what moved, what's next, what's stuck. Anything longer than four lines defeats the purpose —
they will skim it and miss the blocker, which is the one part that actually needs their reaction.

## Arguments

`$ARGUMENTS` may carry the avances, the next goal, blockers, or a specific date. Anything the user
states explicitly wins over anything you infer. Fill only the gaps.

## Steps

### 1. Resolve the date and the path

```bash
date +%F                    # today, unless the user asked for another date
ls docs/progress/           # existing reports, newest name = previous report
```

Target: `docs/progress/<YYYY-MM-DD>.md`. Create `docs/progress/` if missing.

If today's file already exists, read it and update it rather than silently overwriting — the user is
probably refining the same message, not writing a second one.

### 2. Find what actually happened since the last report

The previous report is the watermark: everything before it was already communicated, and repeating it
makes the update look like no progress was made.

```bash
git log --since=<date-of-previous-report> --oneline    # no previous report: use --since="1 week ago"
git log --since=<...> --stat                           # when commit subjects are too terse to judge
git status --short                                     # work in flight, not yet committed
```

Also use this conversation: work done in the session that isn't committed yet, decisions taken,
things the user complained about being stuck on. Git shows what landed; the conversation shows what
it cost and what's next.

### 3. Translate to the reader's language

Commits are written for engineers; this message is not. Say what capability exists now, not which
type class carried it.

- `Phase 3: polymorphic orchestration over F[_] with EitherT/Kleisli` + `fix(domain): remove unreachable paths`
  → *"Terminé la orquestación del cálculo de precios y cerré los hallazgos del code review."*
- Group several commits into one outcome. Four commits on the same feature are one line, not four.
- Drop pure noise: formatting, typos, merge commits, dependency bumps — unless that *was* the work.

### 4. Fill each section

Keep each one to a single sentence. If a section needs two, the second is probably detail the reader
doesn't need.

- **✅ Avances** — what is finished, in the past tense. Only things actually done; work in progress
  belongs in *Próximo objetivo*.
- **🎯 Próximo objetivo** — the next concrete step, not the whole roadmap. Prefer what the user stated;
  otherwise infer from the project's roadmap/specs (e.g. `docs/ROADMAP.md`) or the obvious next step
  after what just landed. If you inferred it, say so when you report back so the user can correct it.
- **🚧 Bloqueos** — only real blockers: something outside the user's control that is stopping them.
  Never invent one to fill the slot; a fabricated blocker sends someone chasing a non-problem. If
  there are none, write `Ninguno.`
- **🙋 Necesito ayuda con** — the concrete ask, phrased so the reader knows what to do. Usually paired
  with a blocker. **Omit this line entirely if there is no ask** — an empty ask trains the team to
  skip the line on the days it matters.

### 5. Write the file

Author name: `git config user.name`. Use the exact format below — the file *is* the message, so it
stays copy-pasteable with no editing.

```markdown
<Nombre del autor>
✅ Avances: <una frase>
🎯 Próximo objetivo: <una frase>
🚧 Bloqueos: <una frase, o "Ninguno.">
🙋 Necesito ayuda con: <una frase — omitir la línea completa si no hay nada>
```

### 6. Report back

Print the message inline so the user can copy it without opening the file, then give the path as a
workspace-relative markdown link. Flag anything you inferred rather than took from the user
(especially the next goal), so they can correct it before sending.

## Example

Previous report: `docs/progress/2026-08-05.md`. Since then: `e112d77 Phase 3: polymorphic
orchestration over F[_] with EitherT/Kleisli`, `e96dc62 fix(domain): remove two unreachable paths
found reviewing this PR`, merged in PR #1. The user mentioned in conversation that they are waiting
on LocalStack credentials.

`docs/progress/2026-08-07.md`:

```markdown
Juan Cadavid
✅ Avances: Terminé la orquestación del cálculo de precios y cerré los hallazgos del code review de la fase 3.
🎯 Próximo objetivo: Montar la Lambda de DynamoDB Streams y probarla contra LocalStack.
🚧 Bloqueos: Sin acceso todavía al entorno de LocalStack del equipo.
🙋 Necesito ayuda con: Que me habiliten las credenciales de LocalStack para poder probar la Lambda.
```

## Constraints

- Write the report in Spanish — it is a message to a Spanish-speaking team, regardless of the
  language of the code or the rest of the repo.
- Only write inside `docs/progress/`. Never touch source code or other project files.
- Never claim work that isn't done. If git and the conversation don't support it, leave it out or ask.
  This message is read as a commitment by whoever plans around it.
- No preamble, no closing, no bullet lists beyond the four lines. Four lines is the whole deliverable.
