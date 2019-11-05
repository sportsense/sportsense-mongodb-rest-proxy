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
import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;

public class MongoDbConfig {
  private String domainName;
  private int portNum;
  private String databaseName;
  private MongoDatabase database;
  private MongoClient client;
  
  /*
   * Basic Database config
   * 1. MongoDB hosted on local host at port number(default) 27017
   * 2. Use database 'testDB'
   */
  public MongoDbConfig() {
    this.domainName = "localhost";
    this.portNum = 27017;
    this.databaseName = "sportsense";
    connectToDB();
  }

  /*
   * Database config
   * dbName: user defined database 
   * domainName: user defined domain where MongoDB is installed
   */
  public MongoDbConfig(String dbName, String domainName) {
    this.domainName = domainName;
    this.portNum = 27017;
    this.databaseName = dbName;  
    connectToDB();
  }

  /*
   * Database config
   * dbName: user defined database 
   * domainName: user defined domain where MongoDB is installed
   * portNum: at user defined port number
   */
  public MongoDbConfig(String dbName, String domainName, int portNum) {
    this.domainName = domainName;
    this.portNum = portNum;
    this.databaseName = dbName;  
    connectToDB();
  }

  /*
   * Connection to database
   */
  private void connectToDB() {
    client = new MongoClient(this.domainName, this.portNum);
    this.database = client.getDatabase(this.databaseName); 
  }

  /*
   * Returns the database object instantiated after
   * creation of connection to MongoDB
   */
  public MongoDatabase getDatabase() {
    return database;
  }
}