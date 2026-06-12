# Fly Machine cost modes — decision record

> **Question to revisit at every meaningful project milestone:**
> *"Which Fly Machine mode do I want right now?"*
>
> The choice changes exactly two lines in [`fly.toml`](../../fly.toml) (`auto_stop_machines` + `min_machines_running`)
> and is fully reversible by the next commit + deploy.

## Three modes

| Mode | `auto_stop_machines` | `min_machines_running` | Idle cost | First request after idle | When to use |
|---|---|---|---|---|---|
| **stop** | `"stop"` | `0` | **~$0** | Full JVM cold start **~10–25 s** (Spring Boot boots from scratch) | Pre-MVP, no real users, prototype, "let it sit there costing nothing" |
| **suspend** | `"suspend"` | `0` | ~$0 + a few cents for the RAM snapshot | **sub-second wake** (RAM is restored from the snapshot) | Demo, certification, low traffic with occasional requests — when cold start is unacceptable but 24/7 warm is overkill |
| **warm** | `false` | `1` | **~$6.80 / mo** (`shared-cpu-1x` 1 GB in `fra`) | **0 s** — the machine is always hot | Production with real traffic, iCalendar feed polled by clients, external monitor pinging every 5 min, when the PRD's 60 s extraction budget cannot be eaten by JVM startup |

## Active mode

**`stop`** — since commit [`9ee9303`](../../fly.toml) (2026-05-22).
Reason: the app is still a scaffold, nobody is using it, every cent matters.

## How to switch

Edit `fly.toml`, change the two lines under `[http_service]`:

```toml
# stop  (current)
auto_stop_machines = "stop"
min_machines_running = 0

# suspend
auto_stop_machines = "suspend"
min_machines_running = 0

# warm
auto_stop_machines = false
min_machines_running = 1
```

Then `git commit && git push` — the `auto-deploy to Fly on push to main`
workflow (commit [`fd27821`](../../.github/workflows/)) will run `flyctl deploy`
and the change takes effect.

## Decision points throughout the project lifecycle

| When | Suggested mode | Why |
|---|---|---|
| Now (scaffold, no features) | `stop` | $0; cold-start is irrelevant — nobody is pinging |
| First real endpoint for manual testing | `stop` (no change) | Single requests, mindful of the ~20 s wait — tolerable |
| One week before certification / demo | `suspend` | Sub-second wake = whoever clicks the link won't see a timeout |
| Clients start consuming the iCalendar feed (polling every 15–60 min) | `warm` | Every poll wakes the machine anyway — cheaper to keep it hot than to pay snapshot storage + cold start per poll |
| Switching UptimeRobot to monitor `/feed` instead of `/actuator/health` | `warm` | 5-min polling = the machine never gets to sleep → suspend has the downsides with none of the upsides |

## Caveats

- **Spring Boot 4 + Java 21 cold start** is realistically 10–25 s on `shared-cpu-1x` with 1 GB RAM.
  The first request after idle will hit a 502/timeout in the browser (Fly holds the TCP connection, but typical HTTP clients have a shorter timeout). The second request will succeed.
- **`suspend`** requires the machine to have enough memory to write the snapshot. For 1 GB RAM it's fine; if you ever scale down to 256 MB, check Fly's docs first to confirm the snapshot fits.
- **`warm` ≠ HA.** `min_machines_running = 1` means "at least one machine", not "exactly one". The first `fly deploy` after switching to warm **may** re-create an HA peer (Fly default since 2024) — if it does, kill it with `flyctl scale count 1 -a ogarniacz`. Lesson from commit [`24c4420`](https://github.com/ludmilabinek/ogarniacz/commit/24c4420).
- **Billing lags behind reality.** After switching to `stop`, the first invoice will still show a tail from the previous mode. Actual billing impact = roughly one billing cycle.

## Related

- [`fly.toml`](../../fly.toml) — where the config lives
- [`deploy-plan.md`](./deploy-plan.md) — full deploy runbook, section D.2 (`fly.toml`) and §4.3 (verification)
- [`context/foundation/infrastructure.md`](../foundation/infrastructure.md) — why Fly, why `fra`, why 1 GB
