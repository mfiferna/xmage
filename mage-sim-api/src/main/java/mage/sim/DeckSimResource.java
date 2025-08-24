package mage.sim;

import com.google.gson.Gson;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.server.game.GameFactory;
import mage.server.game.PlayerFactory;
import mage.players.Player;
import mage.players.PlayerType;

public class DeckSimResource {
    private final Gson gson = new Gson();

    public static class SimRequest {
        public DeckCardLists deckA;
        public DeckCardLists deckB;
        public int games = 10;
        public String aiA = "COMPUTER_MONTE_CARLO";
        public String aiB = "COMPUTER_MONTE_CARLO";
        public String gameType = "Two Player Duel";
    }

    public static class PlayerStats {
        public int wins = 0;
        public int losses = 0;
        public int draws = 0;
        public double avgWinRound = 0.0;
    }

    public static class SimResponse {
        public int gamesRequested;
        public int gamesCompleted;
        public PlayerStats deckA = new PlayerStats();
        public PlayerStats deckB = new PlayerStats();
        public long durationMs;
    }

    public String simulate(String jsonRequest) throws Exception {
        try {
            System.out.println("Received request: " + jsonRequest);
            SimRequest req = gson.fromJson(jsonRequest, SimRequest.class);
            System.out.println("Parsed request for " + req.games + " games");

            long start = System.currentTimeMillis();
            SimResponse resp = new SimResponse();
            resp.gamesRequested = Math.max(1, req.games);

            // load decks
            System.out.println("Loading deck A...");
            Deck deckA = Deck.load(req.deckA, false, false);
            System.out.println("Loading deck B...");
            Deck deckB = Deck.load(req.deckB, false, false);

            // create players
            System.out.println("Creating players...");
            PlayerType pA = PlayerType.valueOf(req.aiA);
            PlayerType pB = PlayerType.valueOf(req.aiB);
            Player playerA = PlayerFactory.instance.createPlayer(pA, "AI-A", null, 1).orElseThrow(() -> new RuntimeException("Failed to create player A"));
            Player playerB = PlayerFactory.instance.createPlayer(pB, "AI-B", null, 1).orElseThrow(() -> new RuntimeException("Failed to create player B"));

            // create match options and match
            System.out.println("Creating match...");
            MatchOptions options = new MatchOptions("sim", req.gameType, false);
            Match match = GameFactory.instance.createMatch(req.gameType, options);
            match.addPlayer(playerA, deckA);
            match.addPlayer(playerB, deckB);

            int completed = 0;
            long sumWinRoundA = 0;
            long sumWinRoundB = 0;

            for (int i = 0; i < resp.gamesRequested; i++) {
                System.out.println("Starting game " + (i + 1) + "/" + resp.gamesRequested);
                match.startMatch();
                match.startGame();
                // after startGame, a Game is created and added to match.getGames()
                // run game synchronously by calling game.start if possible
                // match.getGame().start(null) would run the play loop, but game lifecycle normally handled by GameController
                try {
                    match.getGame().start(null);
                } catch (Exception e) {
                    // rethrow as runtime
                    throw new RuntimeException(e);
                }

                // collect results
                String winner = match.getGame().getWinner();
                System.out.println("Game " + (i + 1) + " winner: " + winner);
                if (match.getGame().isADraw()) {
                    resp.deckA.draws++;
                    resp.deckB.draws++;
                } else {
                    // winner string contains winner name; players' log names are "AI-A"/"AI-B"
                    if (winner != null && winner.contains("AI-A")) {
                        resp.deckA.wins++;
                        resp.deckB.losses++;
                        sumWinRoundA += match.getGame().getTurnNum();
                    } else if (winner != null && winner.contains("AI-B")) {
                        resp.deckB.wins++;
                        resp.deckA.losses++;
                        sumWinRoundB += match.getGame().getTurnNum();
                    }
                }
                completed++;
                match.endGame();
                // cleanup game objects between runs
                match.cleanUp();
            }

            resp.gamesCompleted = completed;
            if (resp.deckA.wins > 0) resp.deckA.avgWinRound = (double) sumWinRoundA / resp.deckA.wins;
            if (resp.deckB.wins > 0) resp.deckB.avgWinRound = (double) sumWinRoundB / resp.deckB.wins;
            resp.durationMs = System.currentTimeMillis() - start;

            String result = gson.toJson(resp);
            System.out.println("Returning result: " + result);
            return result;
        } catch (Exception e) {
            System.err.println("Error in simulate: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
}
