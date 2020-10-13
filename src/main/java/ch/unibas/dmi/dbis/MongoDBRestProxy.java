/*
 * SportSense
 * Copyright (C) 2019  University of Basel
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ch.unibas.dmi.dbis;

import org.bson.Document;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MongoDBRestProxy {
  /**
   * Slf4j logger
   */
  private static final Logger logger = LoggerFactory.getLogger(MongoDBRestProxy.class);
  public static MongoDatabase db;
  public static MongoCollection<Document> eventCollection;
  public static MongoCollection<Document> matchCollection;
  public static MongoCollection<Document> nonatomicEvents;
  public static MongoCollection<Document> queryCollection;
  public static MongoCollection<Document> statesCollection;
  public static MongoCollection<Document> statisticsCollection;
  public static MongoCollection<Document> usersCollection;

  public static void main(String[] args) {
    MongoDBRestProxy dbProxy = new MongoDBRestProxy();
  }

  public MongoDBRestProxy() {
    int port = 2222;
    logger.info("Starting MongoDB Rest Proxy on port {}", port);

    /*
     * Make Database connection
     */
    MongoDbConfig dbConfig = new MongoDbConfig();
    MongoDBRestProxy.db = dbConfig.getDatabase();
    MongoDBRestProxy.eventCollection = db.getCollection("events");
    MongoDBRestProxy.matchCollection = db.getCollection("matches");
    MongoDBRestProxy.nonatomicEvents = db.getCollection("nonatomicEvents");
    MongoDBRestProxy.queryCollection = db.getCollection("savedFilters");
    MongoDBRestProxy.statesCollection = db.getCollection("states");
    MongoDBRestProxy.statisticsCollection = db.getCollection("statistics");
    MongoDBRestProxy.usersCollection = db.getCollection("users");


    Server server = new Server();

    // Hack: 1MB instead of 8KB in order to enable many GET parameters (Attention: Vulnerable to DOS
    // attacks)
    HttpConfiguration httpConf = new HttpConfiguration();
    httpConf.setRequestHeaderSize(1048576);
    httpConf.setSecureScheme("http");
    ServerConnector serverConnector =
        new ServerConnector(server, new HttpConnectionFactory(httpConf));
    serverConnector.setPort(port);
    server.setConnectors(new Connector[] {serverConnector});

    RequestHandler requestHandler = new RequestHandler(this);
    server.setHandler(requestHandler);
    try {
      server.start();
      server.join();
    } catch (Exception e) {
      // logger.error("Caught exception during Jetty server start or
      // join.", e);
    } finally {

    }
  }
}
