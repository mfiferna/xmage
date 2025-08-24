## Deck Simulator: status and implementation plan

Date: 2025-08-23

### Quick receipt
You asked for a standalone HTTP API that accepts two decklists and a number of games and returns matchup statistics; below is a concise summary of the current status and a complete implementation plan including everything learned from the codebase so far.

---

## 1) Current status (what I did so far)
- Performed codebase reconnaissance across core engine and server modules.
- Verified core building blocks are present:
  - Deck model and loaders: `mage.cards.decks.Deck`, `Deck.load(DeckCardLists, ...)` and `DeckCardLists` / `DeckCardInfo`.
  - Match & Game lifecycle: `mage.game.match.MatchImpl`, concrete match types in plugin modules (e.g., `mage-game-twoplayerduel` -> `TwoPlayerMatch` and `TwoPlayerDuel`).
  - Player creation: `mage.server.game.PlayerFactory` and `mage.players.PlayerType` (AI options available: COMPUTER_MONTE_CARLO, COMPUTER_MAD, COMPUTER_DRAFT_BOT, HUMAN).
  - Game play loop: `GameImpl.start(UUID)` -> `play(...)` (the engine runs the full game loop inside `Game.start`).
- Surveyed server side orchestration: `Mage.Server` uses `TableController`, `GameController` and `GameWorker` to run games in threads for connected users — but these are not required for a standalone simulation.
- Created a PoC standalone module skeleton `mage-sim-api` (new module added to the aggregator). It contains:
  - `mage.sim.Main` (embedded Jetty + Jersey) to host an endpoint
  - `mage.sim.DeckSimResource` (JAX-RS resource) with a `POST /api/simulate` that accepts `DeckCardLists` JSON and a games count and runs games sequentially
  - A README with a short DeckCardLists example
- Attempted to build the new module; the first compile failed due to missing `mage.server.game` references. I added `mage-server` and the two-player plugin as dependencies. A subsequent build aborted (exit code 130) during the reactor build; further build/debug can be done next.

## 2) What I learned that matters for implementation
- Deck input format
  - The in-repo POJO for client-side decks is `mage.cards.decks.DeckCardLists` containing `name`, `author`, `cards` (List of `DeckCardInfo`) and `sideboard`.
  - `DeckCardInfo` fields: `cardName`, `setCode`, `cardNumber`, `amount` — these are sufficient to call `Deck.load(DeckCardLists, false, false)` which resolves real Card objects via the card repository.
  - Example JSON snippet (client-side DeckCardLists):

```json
{
  "name": "Example Deck",
  "author": "me",
  "cards": [
    { "cardName": "Island", "cardNumber": "1", "setCode": "UNH", "amount": 24 },
    { "cardName": "Opt", "cardNumber": "2", "setCode": "XLN", "amount": 4 }
  ],
  "sideboard": []
}
```

- Running games programmatically
  - You can create `Deck` objects via `Deck.load(...)` and create `Player` objects via `PlayerFactory.instance.createPlayer(PlayerType, name, range, skill)`.
  - You can create a `Match` either via `GameFactory.instance.createMatch(gameType, MatchOptions)` or instantiate the plugin match class directly.
  - `Match.startGame()` creates and attaches a `Game` to the match; the actual engine's `Game.start(UUID)` runs the game loop and returns on completion. For AI-only simulations we can call `game.start(null)` in the same thread to run the game synchronously (this bypasses the server's `GameController` threading and user session management and is appropriate for a simulation PoC).

- Important classes and locations
  - Deck format POJOs: `Mage/src/main/java/mage/cards/decks/DeckCardLists.java`, `DeckCardInfo.java`, `Deck.java`.
  - Match & game lifecycles: `Mage/src/main/java/mage/game/match/MatchImpl.java`, `Mage/src/main/java/mage/game/GameImpl.java`.
  - Two-player concrete match: `Mage.Server.Plugins/Mage.Game.TwoPlayerDuel` (`TwoPlayerMatch`, `TwoPlayerDuel`).
  - Player factory: `Mage.Server/src/main/java/mage/server/game/PlayerFactory.java` and `Mage/src/main/java/mage/players/PlayerType.java`.

## 3) Design decision (chosen approach)
- Provide a standalone module `mage-sim-api` that runs an embedded HTTP server and calls the engine directly. Reasons:
  - Low-risk: no changes to the main production server or its remoting stack.
  - Faster to iterate and test locally.
  - Simple to deploy as a separate process that can be scaled or sandboxed.

API contract (PoC)
- POST /api/simulate
  - Request body JSON (fields):
    - `deckA`: DeckCardLists JSON (required)
    - `deckB`: DeckCardLists JSON (required)
    - `games`: integer (optional, default 10)
    - `aiA`, `aiB`: string (optional, values from `PlayerType` enum e.g., `COMPUTER_MONTE_CARLO`) 
    - `gameType`: string (optional, default `Two Player Duel`)
  - Response JSON: games requested/completed, deckA/deckB stats (wins/losses/draws, avg win round), duration, seed

Implementation constraints and important notes
- Deck resolution: `Deck.load` expects real card info (set code and card number must match repository). If a deck contains unknown cards, `Deck.load` throws a `GameException`. For convenience we could optionally accept `mockCards` (which creates mock cards but AI can't play mock cards reliably), or we can pre-validate inputs.
- Long games: some decks may produce long-running games. Implement per-game timeouts by running each `game.start` in an `ExecutorService` with a timeout and a policy for timed-out games (count as draw/forfeit or retry). The PoC currently runs games sequentially without a timeout; add timeout for production.
- Reproducibility: include RNG seed in responses for reproducibility and allow optional request seed.
- Concurrency & resources: sequential synchronous simulation is simplest; later we can parallelize across worker threads with a global concurrency limit and queue.

## 4) Complete implementation plan (step-by-step)
This is an actionable plan to finish the standalone API from PoC -> production-ready.

Phase A — PoC (already started)
1. Create a new Maven module `mage-sim-api` in the reactor (done).
2. Add an embedded HTTP server and a small JAX-RS resource that accepts `DeckCardLists` JSON and an integer `games` (PoC resources created: `mage.sim.Main`, `mage.sim.DeckSimResource`).
3. Implement synchronous simulation loop using engine calls:
   - `Deck.load` to create `Deck` objects
   - `PlayerFactory.instance.createPlayer(...)` to create AI players
   - create `Match` via `GameFactory.instance.createMatch(gameType, options)` or instantiate `TwoPlayerMatch`
   - `match.addPlayer(...)`, `match.startMatch()`, `match.startGame()`, `game.start(null)` to run the game
   - collect `game.getWinner()`, `game.isADraw()`, `game.getTurnNum()` etc. (PoC implemented)
4. Validate basic end-to-end operation locally (build & run). If build fails because of initialization or missing runtime resources, adjust module dependencies or small code changes.

Phase B — Reliability & correctness
1. Add per-game timeout and interruption handling. Use `ExecutorService.submit(Callable)` with `.get(timeout, TimeUnit.SECONDS)`; on timeout, cancel and decide policy (count as draw or loss).
2. Add input validation and helpful error responses for common Deck.load failures (return message from GameException).
3. Add reproducible RNG: optionally accept a `seed` parameter and set RandomUtil seed if engine allows (or pass seed into engine RNG where doable). Document that full reproducibility may require deterministic engine configuration.
4. Add configurable AI types and skill levels in API; validate `PlayerType` and provide sensible defaults.

Phase C — Performance & scaling
1. Allow concurrent simulations using a bounded thread pool and a queue. Add configuration for parallelism.
2. Add metrics (requests, avg duration, failures) and basic rate-limiting.
3. Add persistence option to store simulation runs and results in a SQLite DB (optional), reusing existing server DB libraries if desired.

Phase D — Productization
1. Add authentication/ACL if the service will be publicly accessible.
2. Add advanced input formats: support plain-text decklists and MTGJson via existing `DeckImporter` utilities.
3. Add a web UI to submit decks and view results.

## 5) Verification plan / tests
- Unit tests for Deck parsing and small deterministic games using tiny decks already present in `Mage.Tests`.
- Integration test: run the HTTP server locally and POST two known small decks for a small number of games, assert that API returns valid JSON and no exceptions.
- Smoke test: run 100 games locally to verify resource usage and observe timeouts.

## 6) Commands / how to run (PoC)
- Build only the sim module (from repo root):

```bash
mvn -DskipTests install -pl mage-sim-api -am
```

- Run the PoC server (module exec):

```bash
mvn -pl mage-sim-api exec:java -Dexec.args="8081"
```

- Example request body (DeckCardLists JSON, minimal):

```json
{
  "deckA": { "name": "Deck A", "author": "me", "cards": [ { "cardName": "Island", "cardNumber": "1", "setCode": "UNH", "amount": 24 } ], "sideboard": [] },
  "deckB": { "name": "Deck B", "author": "me", "cards": [ { "cardName": "Mountain", "cardNumber": "1", "setCode": "UNH", "amount": 24 } ], "sideboard": [] },
  "games": 10
}
```

## 7) Risks & open questions
- DeckCardLists input must reference cards present in the local `CardRepository`; otherwise `Deck.load` will error. Do you want to accept plain text lists and try to auto-resolve names? If so we will add an importer step.
- How should we treat timed-out games? (draw, forfeit, retry). Recommend counting as a draw for safety.
- Do you want parallel runs on the same JVM or a queue of worker processes? If you expect high throughput, containerized workers may be better.

## 8) Next steps I can take now (pick one)
1. Finish the PoC build errors and get the sim server runnable locally (fix any missing dependency or classpath issues and run a short test). — I can do this now.
2. Extend the PoC with timeouts, better error handling, and a small test harness.  — I can do this next after (1).
3. Add support for plain-text decklist parsing and MTGJson import. — subsequent work.

---

If you want me to continue, tell me which next step you prefer (I suggest finishing the PoC build + run). I will proceed and report back with a short progress update and then implement the next items.
