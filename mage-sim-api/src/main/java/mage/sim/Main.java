package mage.sim;

import static spark.Spark.*;

public class Main {

     public static void main(String[] args) {
         int port = 8081;
         if (args.length > 0) {
             try {
                 port = Integer.parseInt(args[0]);
             } catch (Exception ignored) {
             }
         }

         port(port);

         // Create an instance of the resource
         DeckSimResource resource = new DeckSimResource();

         // Define routes
         get("/api/test", (req, res) -> {
             return "{\"message\":\"API is working\"}";
         });

         post("/api/simulate", (req, res) -> {
             res.type("application/json");
             try {
                 return resource.simulate(req.body());
             } catch (Exception e) {
                 e.printStackTrace();
                 return "{\"error\":\"" + e.getMessage() + "\"}";
             }
         });

         // Add CORS headers for web clients
         after((req, res) -> {
             res.header("Access-Control-Allow-Origin", "*");
             res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
             res.header("Access-Control-Allow-Headers", "Content-Type, Authorization, Content-Length, X-Requested-With");
         });

         options("/*", (req, res) -> {
             res.header("Access-Control-Allow-Origin", "*");
             res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
             res.header("Access-Control-Allow-Headers", "Content-Type, Authorization, Content-Length, X-Requested-With");
             return "";
         });

         System.out.println("Deck simulator API started on port " + port);
         System.out.println("POST /api/simulate to run simulations");
     }
 }
