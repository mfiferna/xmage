Deck simulator API (PoC)

POST /api/simulate

Example request JSON (DeckCardLists format used by XMage):

{
  "deckA": {
    "name": "Deck A",
    "author": "tester",
    "cards": [
      { "cardName": "Island", "cardNumber": "1", "setCode": "UNH", "amount": 24 },
      { "cardName": "Opt", "cardNumber": "2", "setCode": "XLN", "amount": 4 }
    ],
    "sideboard": []
  },
  "deckB": {
    "name": "Deck B",
    "author": "tester",
    "cards": [
      { "cardName": "Mountain", "cardNumber": "1", "setCode": "UNH", "amount": 24 },
      { "cardName": "Lightning Bolt", "cardNumber": "2", "setCode": "M10", "amount": 4 }
    ],
    "sideboard": []
  },
  "games": 10
}

Notes:
- The request format is the `DeckCardLists` JSON mapping (fields: name, author, cards (array of DeckCardInfo), sideboard).
- This PoC uses `Deck.load(deckCardLists, false, false)` so card set codes/numbers must match the local card repository for real card objects; otherwise Deck.load will throw a GameException.
- Start the server with `mvn -pl mage-sim-api exec:java -Dexec.args="8081"` from repo root (after building).
