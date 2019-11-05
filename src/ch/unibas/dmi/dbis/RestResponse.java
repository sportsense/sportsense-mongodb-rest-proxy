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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.json.JSONArray;
import org.json.JSONObject;
import com.google.gson.Gson;
import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;

public class RestResponse {
  /**
   * HTTP status code
   */
  private int httpStatusCode;

  /**
   * Content
   */
  private String content;

  RestResponse() {
    this.httpStatusCode = 200;
    this.content = "";
  }

  public void getDefaultResponse() {
    this.httpStatusCode = 200;
    this.content = "This is default Response!";
  }

  public void getAreaEvent(HttpServletRequest request) {

    // Initialize cursors for database results
    MongoCursor<Document> cursor = null;
    // MongoCursor<Document> cursorVideo = null;

    // System.out.println("the coordinates are " + request.getParameter("coordinates").toString());

    List<Bson> queryFilters = new ArrayList<Bson>();

    // Read the shape details and filters
    String shapeType;
    JSONObject shapeContent;
    Boolean highlights;
    if (request.getParameterMap().containsKey("shape")) {
      highlights = false;
      shapeType = request.getParameter("shape");
      shapeContent = new JSONObject(request.getParameter("coordinates"));
    } else {
      highlights = true;
      shapeType = null;
      shapeContent = null;
    }

    getFilters(queryFilters, request);
    getGeoQuery(queryFilters, shapeType, shapeContent);

    // To skip over the events that are not relevant for the video analysis.
    queryFilters.add(Filters.ne("type", "areaEvent"));
    queryFilters.add(Filters.ne("type", "ballPossessionChangeEvent"));
    queryFilters.add(Filters.ne("type", "dribblingEvent"));
    queryFilters.add(Filters.ne("type", "duelEvent"));
    queryFilters.add(Filters.ne("type", "kickEvent"));
    queryFilters.add(Filters.ne("type", "matchTimeProgressEvent"));
    queryFilters.add(Filters.ne("type", "speedLevelChangeEvent"));
    queryFilters.add(Filters.ne("type", "underPressureEvent"));

    /*
     * Query the database
     */

    // System.out.println(queryFilters.toString());

    cursor = MongoDBRestProxy.eventCollection.find(Filters.and(queryFilters))
        .sort(new BasicDBObject("time", 1)).iterator();
    // MongoCursor<Document> nonAtomicCursor =
    // MongoDBRestProxy.nonatomicEvents.find(Filters.and(queryFilters)).sort(new
    // BasicDBObject("time", 1))
    // .iterator();

    JSONObject jsonObj = null;
    JSONObject resultJsonObj = null;
    JSONArray finalResultArray = new JSONArray();
    JSONArray finalVideoArray = new JSONArray();
    JSONObject finalResult = new JSONObject();
    /*
     * Loop through database result to form JSON string for web result
     */
    try {
      while (cursor.hasNext()) {
        jsonObj = new JSONObject(cursor.next().toJson());
        resultJsonObj = new JSONObject();

        resultJsonObj.append("details", jsonObj);
        finalResultArray.put(resultJsonObj);

      } // do special query with interception/misplacedPasses

      // while (nonAtomicCursor.hasNext()) {
      // jsonObj = new JSONObject(nonAtomicCursor.next().toJson());
      // resultJsonObj = new JSONObject();
      // resultJsonObj.append("details", jsonObj);
      // finalResultArray.put(resultJsonObj);
      // }

      if (highlights) {
        JSONObject matchFilters = new JSONObject(request.getParameter("matchFilters"));
        queryFilters = new ArrayList<Bson>();
        queryFilters.add(Filters.or(getBsonArray(matchFilters, "matchId")));
        cursor = MongoDBRestProxy.matchCollection.find(Filters.and(queryFilters)).iterator();
        jsonObj = new JSONObject(cursor.next().toJson());
        finalResult.append("matchDetails", jsonObj);
      }
    } finally {
      /*
       * Close the cursors
       */
      cursor.close();

      /*
       * Create final result to be sent back to client
       */
      finalResult.append("videoDetails", finalVideoArray);
      finalResult.append("result", finalResultArray);
    }

    /*
     * Set content for HttpResponse
     */

    this.content = finalResult.toString();
    // System.out.println("the response is " + this.content);
  }

  /**
   * This function starts an event cascade. The function checks if this is a forward or a backward
   * event cascade and executes the corresponding function.
   * 
   * @param request
   */
  public void getEventCascade(HttpServletRequest request) {
    List<Bson> queryFilters = new ArrayList<Bson>();
    // System.out.print("Request: " + request);
    String reverse = request.getParameter("reverse");
    String threshold = request.getParameter("threshold");
    JSONArray ts = new JSONArray(request.getParameter("timestamps"));

    getFilters(queryFilters, request);

    Bson[] b = null;
    String[] ids = null;
    String[] matchIds = null;

    if (reverse.equals("false")) {
      String shapeType = request.getParameter("shape");
      JSONObject shapeContent = new JSONObject(request.getParameter("coordinates"));

      Object[] tmp = forwardEventCascade(queryFilters, shapeType, shapeContent, ts, threshold);
      b = (Bson[]) tmp[0];
      ids = (String[]) tmp[1];
      matchIds = (String[]) tmp[2];

    } else if (reverse.equals("true")) {
      Object[] tmp = reverseEventCascade(queryFilters, ts, threshold);
      b = (Bson[]) tmp[0];
      ids = (String[]) tmp[1];
      matchIds = (String[]) tmp[2];
    }

    JSONObject finalResult = new JSONObject();

    for (int i = 0; i < b.length; i++) {
      MongoCursor<Document> cursor = null;
      JSONObject jsonObj = null;
      JSONObject resultJsonObj = null;
      JSONArray finalResultArray = new JSONArray();
      // JSONArray finalVideoArray = new JSONArray();

      List<Bson> tmp = new ArrayList<Bson>(queryFilters);
      tmp.add(b[i]);

      // This leads to an increase in performance of Reverse Event Cascades
      if (reverse.equals("true")) {
        tmp.add(Filters.eq("matchId", matchIds[i]));
      }
      // These events happen too frequently though they should not be considered in Event Cascades
      tmp.add(Filters.ne("type", "passSequenceEvent"));
      tmp.add(Filters.ne("type", "areaEvent"));
      tmp.add(Filters.ne("type", "ballPossessionChangeEvent"));
      tmp.add(Filters.ne("type", "dribblingEvent"));
      tmp.add(Filters.ne("type", "duelEvent"));
      tmp.add(Filters.ne("type", "kickEvent"));
      tmp.add(Filters.ne("type", "matchTimeProgressEvent"));
      tmp.add(Filters.ne("type", "speedLevelChangeEvent"));
      tmp.add(Filters.ne("type", "underPressureEvent"));

      // HACK TO NOT CONSIDER OPTA CLEARANCE AND INTERCEPTION EVENTS (they have only one x/y
      // coordinate-pair)
      // TODO: Add all OPTA Match IDs and apply the if statement
      String optaMatchID = "742569";
      if (matchIds[i].equals(optaMatchID)) {
        tmp.add(Filters.eq("type", "clearanceEvent"));
        tmp.add(Filters.eq("type", "interceptionEvent"));
      }

      try {
        cursor = MongoDBRestProxy.eventCollection.find(Filters.and(tmp))
            .sort(new BasicDBObject("ts", 1)).iterator();

        while (cursor.hasNext()) {
          jsonObj = new JSONObject(cursor.next().toJson());
          if (!cursor.hasNext()) {
            resultJsonObj = new JSONObject();
            resultJsonObj.append("id", ids[i]);
            resultJsonObj.append("details", jsonObj);
            finalResultArray.put(resultJsonObj);
          }
        }

      } finally {
        /*
         * Close the cursors
         */
        cursor.close();

        /*
         * Create final result to be sent back to client if length = 0, no result was found and no
         * object needs to be added to the finalResultArray.
         */
        if (finalResultArray.length() > 0) {
          finalResult.append("result", finalResultArray);
        }
      }
    }
    /*
     * Set content for HttpResponse
     */
    this.content = finalResult.toString();
    // System.out.print(" Output: " + this.content);
  }

  /**
   * This function adds filters to the bson list in order to find events within a certain area AND
   * within a certain time slot. Because this is a forward event cascade the time slot is right
   * after the searched time (time stamp + the threshold value).
   * 
   * @param queryFilters
   * @param shapeType
   * @param shapeContent
   * @param ts
   * @param threshold
   */
  private Object[] forwardEventCascade(List<Bson> queryFilters, String shapeType,
      JSONObject shapeContent, JSONArray ts, String threshold) {

    getGeoQuery(queryFilters, shapeType, shapeContent);

    Bson[] b = new Bson[ts.length()];
    String[] ids = new String[ts.length()];
    String[] matchIds = new String[ts.length()];

    for (int key = 0; key < ts.length(); key++) {
      JSONObject elem = ts.getJSONObject(key);
      long time1 = elem.getLong("t");
      long time2 = time1 + Long.parseLong(threshold);

      b[key] = Filters.and(Filters.gt("ts", time1), Filters.lt("ts", time2));
      ids[key] = elem.getString("id");
      matchIds[key] = elem.getString("matchId");
    }

    Object[] r = new Object[3];
    r[0] = b;
    r[1] = ids;
    r[2] = matchIds;

    return r;
  }

  /**
   * This function adds filters to the bson list in order to find events within a certain area AND
   * within a certain time slot. Because this is a reverse event cascade the time slot is right
   * before the searched time (time stamp - the threshold value). New cmt
   * 
   * @param queryFilters
   * @param ts
   */
  private Object[] reverseEventCascade(List<Bson> queryFilters, JSONArray ts, String threshold) {

    Bson[] b = new Bson[ts.length()];
    String[] ids = new String[ts.length()];
    String[] matchIds = new String[ts.length()];

    for (int key = 0; key < ts.length(); key++) {
      JSONObject elem = ts.getJSONObject(key);
      long time1 = elem.getLong("time");
      long time2 = time1 - Long.parseLong(threshold);

      b[key] = Filters.and(Filters.gt("ts", time2), Filters.lt("ts", time1));
      ids[key] = elem.getString("id");
      matchIds[key] = elem.getString("matchId");
    }

    Object[] r = new Object[3];
    r[0] = b;
    r[1] = ids;
    r[2] = matchIds;

    return r;
  }

  /**
   * This function starts a motion path query.
   * 
   * @return
   */
  public void getMotionPath(HttpServletRequest request) {
    // Construct query filter
    // System.out.print(request);
    List<Bson> queryFilters = new ArrayList<Bson>();
    String shapeType = request.getParameter("shape");

    // matchIDFilter only contained in Offball-MP requests
    if (request.getParameterMap().containsKey("matchIDFilter")) {
      String matchID = request.getParameter("matchIDFilter");
      queryFilters.add(Filters.eq("matchId", matchID));
    }

    JSONObject shapeContent = new JSONObject(request.getParameter("coordinates"));

    getFilters(queryFilters, request);
    getGeoQuery(queryFilters, shapeType, shapeContent);

    JSONObject playerFilters = new JSONObject(request.getParameter("playerFilters"));
    queryFilters.add(Filters.or(getBsonArray(playerFilters, "playerIds")));
    queryFilters.add(Filters.eq("type", "fieldObjectState"));

    // Parameters
    int maxTsDiff = 1000;
    double minPathLength = 0.0;

    boolean requireMinPathLength = false;

    if (requireMinPathLength = "true".equals(request.getParameter("minPathLength"))) {
      // OffBall MP vs. Straight and Freehand MP
      if (request.getParameterMap().containsKey("matchIDFilter")) {
        minPathLength = 2.0;
      } else {
        minPathLength = 25.0;
      }
    }

    // Perform MongoDB query and sort by (ts and) matchId
    FindIterable<Document> trackingData =
        MongoDBRestProxy.statesCollection.find(Filters.and(queryFilters))
            /* .sort(new BasicDBObject("ts", 1)) */.sort(new Document("matchId", 1));

    // get all players that are involved in the query and create a path object for them
    HashMap<String, ArrayList<Document>> playerPaths = new HashMap<>();
    for (String key : playerFilters.keySet()) {
      String player = playerFilters.getString(key);
      ArrayList<Document> fullPlayerPath = new ArrayList<>();

      playerPaths.put(player, fullPlayerPath);
    }

    // assign tracking data to correct player path
    for (Document trackingDataItem : trackingData) {
      try {
        // to which player does this tracking position belong?
        String curPlayer = ((ArrayList<String>) trackingDataItem.get("playerIds")).get(0);

        // could not retrieve player or player does not exist in path map -> go to next tracking
        // point
        if (curPlayer == null || !playerPaths.containsKey(curPlayer)) {
          continue;
        }

        // add tracking position to players path
        playerPaths.get(curPlayer).add(trackingDataItem);

      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    // now we actually build json path objects out of the tracking data
    JSONArray resultJsonArray = new JSONArray();

    for (String curPlayerId : playerPaths.keySet()) {
      ArrayList<Document> path = playerPaths.get(curPlayerId);
      // basic things that we need for every path
      JSONObject curPathJsonObj = new JSONObject(); // the object holding all the info about the
                                                    // path
      JSONArray curPathLocationArray = new JSONArray(); // the resulting path itself

      double curPathLength = 0.0;
      String curPathMatchId = null;

      int lastTs = -1;
      double lastX = 0.0; // dummy initialization
      double lastY = 0.0; // dummy initialization

      boolean firstItem = true; // we get some of the path data from the first item in the list

      for (Document trackingDataItem : path) {
        // Get coordinates, matchId and timestamp of current trackingDataItem
        JSONArray curXYArray = new JSONArray(trackingDataItem.get("xyCoords").toString());
        JSONArray curXY = new JSONArray(curXYArray.get(0).toString());
        double curX = curXY.getDouble(0);
        double curY = curXY.getDouble(1);

        String curMatchId = trackingDataItem.getString("matchId");
        int curTs = trackingDataItem.getInteger("ts");
        ObjectId curId = (ObjectId) trackingDataItem.get("_id");
        int curVideoTs = trackingDataItem.getInteger("videoTs");

        // Set path matchId and startTs for very first trackingDataItem
        if (firstItem) { // this means that we're looking at the first item in the tracking data
                         // list
          firstItem = false;
          curPathMatchId = curMatchId;

          mpSetUpPathObject(curPathJsonObj, curMatchId, curId, curTs, curVideoTs, curPlayerId);
        }

        // Check if the path should be ended due to data inconsistencies or time differences
        boolean continuePath = true;

        if (!curPathMatchId.equals(curMatchId)) { // path is not in the same match
          continue; // this data point does not belong to the same match and thus we ignore it
        }
        if (lastTs != -1 && (curTs - lastTs) > maxTsDiff) { // time difference to last tracking data
                                                            // item too high
          continuePath = false;
        }

        if (continuePath) { // normal continuation of path
          // Add point to path
          curPathLocationArray.put(curXY);
          // Calculate distance
          curPathLength += Math.sqrt(Math.pow(curX - lastX, 2.0) + Math.pow(curY - lastY, 2.0));

        } else { // there is a gap in the data!
          // last path was long enough -> add to result
          if (!requireMinPathLength || curPathLength >= minPathLength) {
            mpFinishPathObject(curPathJsonObj, lastTs, curPathLength, curPathLocationArray);
            resultJsonArray.put(curPathJsonObj);
          }

          // start a new path with same player
          curPathLength = 0.0;
          curPathJsonObj = new JSONObject();
          curPathLocationArray = new JSONArray(); // the resulting path itself

          mpSetUpPathObject(curPathJsonObj, curMatchId, curId, curTs, curVideoTs, curPlayerId);
        }

        lastTs = curTs;
        lastX = curX;
        lastY = curY;
      }

      // put rest of info into path object and add path object to result object
      if (curPathLength >= minPathLength) {
        mpFinishPathObject(curPathJsonObj, lastTs, curPathLength, curPathLocationArray);
        resultJsonArray.put(curPathJsonObj);
      }
    }

    this.content = resultJsonArray.toString();
    // System.out.print(this.content);
  }

  /**
   * Small helper method which puts the data into the current path object, that we already know from
   * the start of the path
   */
  private void mpSetUpPathObject(JSONObject curPathJsonObj, String curMatchId, ObjectId curId,
      int curTs, int curVideoTs, String curPlayerId) {
    curPathJsonObj.append("matchId", curMatchId);
    curPathJsonObj.append("eventType", "MotionPath");
    curPathJsonObj.append("_id", curId);
    curPathJsonObj.append("startTime", curTs);
    curPathJsonObj.append("videoTime", curVideoTs);
    curPathJsonObj.append("playerId", curPlayerId);
  }

  /**
   * Small helper method which puts the data into the current path object, that we only know at the
   * end of the path
   */
  private void mpFinishPathObject(JSONObject curPathJsonObj, int lastTs, double curPathLength,
      JSONArray curPathLocationArray) {
    curPathJsonObj.append("endTime", lastTs);
    curPathJsonObj.append("length", curPathLength);
    curPathJsonObj.append("motionPath", curPathLocationArray);
  }

  /**
   * This function uses the input shape type and the input shape content in order to generate a
   * query to filter for geo data.
   * 
   * @param queryFilters
   * @param shapeType
   * @param shapeContent
   */
  private void getGeoQuery(List<Bson> queryFilters, String shapeType, JSONObject shapeContent) {
    // adding geo queries
    if (shapeType == null) {
      // do not add any filter, when shapeType does not exist!
    } else if (shapeType.equals("rectangle")) {
      queryFilters.add(Filters.geoWithinBox("xyCoords.0", shapeContent.getDouble("bottomLeftX"),
          shapeContent.getDouble("bottomLeftY"), shapeContent.getDouble("upperRightX"),
          shapeContent.getDouble("upperRightY")));
    } else if (shapeType.equals("circle")) {
      queryFilters.add(Filters.geoWithinCenter("xyCoords.0", shapeContent.getDouble("centerX"),
          shapeContent.getDouble("centerY"), shapeContent.getDouble("radius")));
    } else if (shapeType.equals("polygon")) {
      final List<List<Double>> polygons = new ArrayList<>();
      JSONArray vertices = shapeContent.getJSONArray("vertices");
      JSONArray coord = null;
      for (int i = 0; i < vertices.length(); i++) {
        coord = vertices.getJSONArray(i);
        polygons.add(Arrays.asList(coord.getDouble(0), coord.getDouble(1)));
      }
      queryFilters.add(Filters.geoWithinPolygon("xyCoords.0", polygons));
    } else {
      System.err.println("Invalid shape type!");
    }
  }

  /**
   * Generic method to concatenate 2 arrays
   * 
   * @param first
   * @param second
   * @param <T>
   * @return
   */
  public static <T> T[] concat(T[] first, T[] second) {
    T[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  /**
   * Overloads the getfilters method so it can be called with a request and with a JSON object used
   * in the "rerunQuery" method
   * 
   * @param queryFilters
   * @param request
   */
  private void getFilters(List<Bson> queryFilters, HttpServletRequest request) {
    JSONObject eventFilters = new JSONObject(request.getParameter("eventFilters"));
    JSONObject teamFilters = new JSONObject(request.getParameter("teamFilters"));
    JSONObject playerFilters = new JSONObject(request.getParameter("playerFilters"));
    JSONObject periodFilters = new JSONObject(request.getParameter("periodFilters"));
    JSONObject matchFilters = new JSONObject(request.getParameter("matchFilters"));
    JSONObject sportFilter = new JSONObject(request.getParameter("sportFilter"));
    JSONObject timeFilter = new JSONObject(); // In the reverse event cascade no timefilter is
                                              // specified, thats why we have to check if it exists
    if (request.getParameterMap().containsKey("timeFilter")) {
      timeFilter = new JSONObject(request.getParameter("timeFilter"));
    }

    getFilters(queryFilters, eventFilters, teamFilters, playerFilters, periodFilters, matchFilters,
        sportFilter, timeFilter);
  }


  /**
   * This function takes the HttpServletRequest and checks if one of the filters is active. If a
   * filter is set, the functions adds the filter to the bson list.
   *
   * @param queryFilters
   */
  private void getFilters(List<Bson> queryFilters, JSONObject eventFilters, JSONObject teamFilters,
      JSONObject playerFilters, JSONObject periodFilters, JSONObject matchFilters,
      JSONObject sportFilter, JSONObject timeFilter) {

    // adding event filters
    if (eventFilters.length() != 0) {
      queryFilters.add(Filters.or(getBsonArray(eventFilters, "type")));
    }

    // adding team filters
    if (teamFilters.length() != 0) {
      queryFilters.add(Filters.or(getBsonArray(teamFilters, "teamIds")));
    }

    // adding player filters
    if (playerFilters.length() != 0) {
      queryFilters.add(Filters.or(getBsonArray(playerFilters, "playerIds")));
    }

    // adding period filters
    if (periodFilters.length() != 0) {
      // queryFilters.add(Filters.or(getBsonArray(periodFilters, "additionalData.Period")));
    }

    // adding time filters
    if (timeFilter.length() != 0) {
      queryFilters
          .add(Filters.and(Filters.gte("ts", Integer.parseInt(timeFilter.get("min").toString())),
              Filters.lte("ts", Integer.parseInt(timeFilter.get("max").toString()))));
    }

    // This influences badly the performance of the MPs --> as long as no other sports in DB no use
    // for this functionality
    /*
     * if (sportFilter.length() != 0 && matchFilters.length() == 0) { MongoCursor<Document> cursor =
     * null; String filter = (String) sportFilter.get("sport"); cursor =
     * MongoDBRestProxy.matchCollection.find(Filters.eq("sport", filter)).iterator();
     * 
     * try { int counter = 0; String filter_num = "filter" + 0; while (matchFilters.has(filter_num))
     * { counter++; filter_num = "filter" + counter; } while (cursor.hasNext()) { JSONObject jsonObj
     * = new JSONObject(cursor.next().toJson()); matchFilters.put("filter" + counter,
     * jsonObj.getString("matchId")); counter++; }
     * 
     * } finally { /* Close the cursors
     * 
     * cursor.close(); } }
     */

    // adding match filters
    if (matchFilters.length() != 0) {
      queryFilters.add(Filters.or(getBsonArray(matchFilters, "matchId")));
    }
  }

  /*
   * adds an unknown amount of filter to a bson list
   */
  private Bson[] getBsonArray(JSONObject object, String field) {
    Iterator<?> keys = object.keys();
    Bson[] temp = new Bson[object.length()];
    int it = 0;
    while (keys.hasNext()) {
      String key = (String) keys.next();
      temp[it] = Filters.eq(field, object.get(key));
      it++;
    }
    return temp;
  }

  /*
   * httpStatusCode getter method
   */
  int getHttpStatusCode() {
    return this.httpStatusCode;
  }

  /*
   * content getter method
   */
  String getContent() {
    return this.content;
  }

  /**
   * This function returns all event types that are in the mongodb. TODO: Remove the unwanted
   * events.
   * 
   * @param httpServletRequest
   */
  public void getEventTypes(HttpServletRequest httpServletRequest) {

    MongoCursor<String> cursor =
        MongoDBRestProxy.eventCollection.distinct("type", String.class).iterator();

    ArrayList<String> notWantedEventList = new ArrayList<>();
    notWantedEventList.add("areaEvent");
    notWantedEventList.add("ballPossessionChangeEvent");
    notWantedEventList.add("dribblingEvent");
    notWantedEventList.add("duelEvent");
    notWantedEventList.add("kickEvent");
    notWantedEventList.add("matchTimeProgressEvent");
    notWantedEventList.add("speedLevelChangeEvent");
    notWantedEventList.add("underPressureEvent");
    notWantedEventList.add("kickEvent");

    JSONObject jsonObj = new JSONObject();

    while (cursor.hasNext()) {
      String eventType = cursor.next();
      if (!notWantedEventList.contains(eventType)) {
        jsonObj.append("eventType", eventType);
      }
    }

    // System.out.println(jsonObj);

    // MongoCursor<String> cursor1 = MongoDBRestProxy.nonatomicEvents.distinct("type",
    // String.class).iterator();
    // while (cursor1.hasNext()){
    // jsonObj.append("eventType", cursor1.next());
    // }

    this.content = jsonObj.toString();
  }

  /**
   * This function returns all team names that are in the mongodb and their ids.
   * 
   * @param httpServletRequest
   */
  public void getTeams(HttpServletRequest httpServletRequest) {

    // MongoCursor<String> cursor = MongoDBRestProxy.matchCollection.distinct("homeTeamName",
    // String.class).iterator();
    // MongoCursor<String> cursor2 = MongoDBRestProxy.matchCollection.distinct("awayTeamName",
    // String.class)
    // .iterator();
    // MongoCursor<String> cursor3 = MongoDBRestProxy.matchCollection.distinct("homeTeamId",
    // String.class)
    // .iterator();
    // MongoCursor<String> cursor4 = MongoDBRestProxy.matchCollection.distinct("awayTeamId",
    // String.class)
    // .iterator();

    ArrayList<NameID> teamnames = new ArrayList<>();

    // while (cursor.hasNext()) {
    // String s = cursor.next();
    // NameID nid = new NameID(s, cursor3.next());
    // if (!teamnames.contains(nid)) {
    // teamnames.add(nid);
    // }
    // }
    // while (cursor2.hasNext()) {
    // String s = cursor2.next();
    // NameID nid = new NameID(s, cursor4.next());
    // if (!teamnames.contains(nid)) {
    // teamnames.add(nid);
    // }
    // }

    MongoCursor<Document> documentCursor = MongoDBRestProxy.matchCollection.find().iterator();

    // get all match data and create a list of players and their IDs with no
    // duplicates
    while (documentCursor.hasNext()) {
      Document d = documentCursor.next();
      String a = d.get("awayTeamName").toString();
      String aID = d.get("awayTeamId").toString();
      String h = d.get("homeTeamName").toString();
      String hID = d.get("homeTeamId").toString();

      NameID hnid = new NameID(h, hID);
      NameID anid = new NameID(a, aID);

      if (!teamnames.contains(hnid)) {
        teamnames.add(hnid);
      }
      if (!teamnames.contains(anid)) {
        teamnames.add(anid);
      }
    }

    JSONObject jsonObj = null;
    JSONObject resultJsonObj = new JSONObject();

    for (int i = 0; i < teamnames.size(); i++) {
      jsonObj = new JSONObject();
      NameID t = teamnames.get(i);
      jsonObj.put("tid", t.id);
      jsonObj.put("name", t.name);
      resultJsonObj.append("result", jsonObj);
    }

    documentCursor.close();
    this.content = resultJsonObj.toString();
  }

  /**
   * Gets all the saved queries from the Database, only the name and the ID.
   * 
   * @param httpServletRequest
   */
  public void getQueries(HttpServletRequest httpServletRequest) {
    // To first get the elements that were added last, we sort them in descending order
    MongoCursor<Document> cursor =
        MongoDBRestProxy.queryCollection.find().sort(new BasicDBObject("_id", -1)).iterator();
    List<NameID> queries = new ArrayList<>();

    while (cursor.hasNext()) {
      Document d = cursor.next();
      String queryID = d.get("_id").toString();
      String queryName = d.get("Name").toString();

      NameID q = new NameID(queryName, queryID);
      queries.add(q);
    }

    JSONObject jsonObject = null;
    JSONObject resultJsonObject = new JSONObject();

    for (int i = 0; i < queries.size(); i++) {
      jsonObject = new JSONObject();
      NameID q = queries.get(i);
      jsonObject.put("name", q.name);
      jsonObject.put("qid", q.id);
      resultJsonObject.append("result", jsonObject);
    }
    cursor.close();
    this.content = resultJsonObject.toString();
  }

  public void deleteSavedQuery(HttpServletRequest httpServletRequest) {
    String id = httpServletRequest.getParameter("queryID");
    MongoDBRestProxy.queryCollection.deleteOne(new Document("_id", new ObjectId(id)));
    JSONObject jsonObj = new JSONObject();
    jsonObj.append("id", httpServletRequest.getParameter("queryID"));
    this.content = jsonObj.toString();
  }

  public void addMisplacedPassEventsData(Query q, Document additional) {
    q.misplacedPassEvents++;
    Double length = Double.parseDouble(String.valueOf(additional.get("length")));
    q.totalMisplacedPassLength += length;

    if (additional.getDouble("velocity") != null) {
      q.totalMisplacedPassVelocity += additional.getDouble("velocity");
    }
    if (additional.getString("direction") != null) {
      String passDirection = additional.getString("direction");
      switch (passDirection) {
        case "forward":
          q.misplacedPassForward++;
          break;
        case "backward":
          q.misplacedPassBackward++;
          break;
        case "left":
          q.misplacedPassLeft++;
          break;
        case "right":
          q.misplacedPassRight++;
          break;
      }
    }
    if (q.maxMisplacedPassLength < Double.parseDouble(String.valueOf(additional.get("length")))) {
      q.maxMisplacedPassLength = Double.parseDouble(String.valueOf(additional.get("length")));
    }
    if (additional.getDouble("velocity") != null) {
      if (q.maxMisplacedPassVelocity < additional.getDouble("velocity")) {
        q.maxMisplacedPassVelocity = additional.getDouble("velocity");
      }
    }
    if (length < 7) {
      q.MPunderSevenPasses++;
    } else if (length < 15) {
      q.MPsevenToFifteenPasses++;
    } else if (length < 30) {
      q.MPfifteenToThirtyPasses++;
    } else if (length > 30) {
      q.MPoverThirtyPasses++;
    }
  }

  public void addSuccessfulPassEventsData(Query q, Document additional) {
    q.successfulPassEvents++;
    Double length = Double.parseDouble(String.valueOf(additional.get("length")));
    q.totalPassLength += length;

    if (additional.getDouble("velocity") != null) {
      q.totalPassVelocity += additional.getDouble("velocity");
    }
    if (additional.getInteger("packing") != null) {
      q.totalPacking += additional.getInteger("packing");
    }
    if (additional.getString("direction") != null) {
      String passDirection = additional.getString("direction");
      switch (passDirection) {
        case "forward":
          q.forwardPasses++;
          break;
        case "backward":
          q.backwardPasses++;
          break;
        case "left":
          q.leftPasses++;
          break;
        case "right":
          q.rightPasses++;
          break;
      }
    }
    if (q.maxPassLength < Double.parseDouble(String.valueOf(additional.get("length")))) {
      q.maxPassLength = Double.parseDouble(String.valueOf(additional.get("length")));
    }
    if (additional.getDouble("velocity") != null) {
      if (q.maxPassVelocity < additional.getDouble("velocity")) {
        q.maxPassVelocity = additional.getDouble("velocity");
      }
    }
    if (length < 7) {
      q.underSevenPasses++;
    } else if (length < 15) {
      q.sevenToFifteenPasses++;
    } else if (length < 30) {
      q.fifteenToThirtyPasses++;
    } else if (length > 30) {
      q.overThirtyPasses++;
    }
  }

  public boolean eventInTimeFilter(double min, double max, String matchID, int kickoffTs,
      Document doc) {
    if (min != 0.00 || max != 90.00) {
      if (matchID.equals(doc.getString("matchId")) != true) { // If we have a different matchID than
                                                              // from previous events. This is done
                                                              // to not query it for every event
        matchID = doc.getString("matchId");
        FindIterable<Document> findIterable = MongoDBRestProxy.eventCollection
            .find(new Document("matchId", matchID).append("type", "kickoffEvent"));
        Document k = findIterable.first();
        if (k != null) { // Check whether we found a kickoffevent with that id or not.
          kickoffTs = k.getInteger("ts"); // Here we get the time of kickoffEvent, this we use to
                                          // subract from the other events.
        }
      }
      int eventTs = doc.getInteger("ts"); // Get the timeStamp from the Event
      int matchTs = eventTs - kickoffTs; // Here we get the eventTS from a match starting at 0.00.

      double msMin = min * 60000;
      double msMax = max * 60000; // to convert into milliseconds

      if (matchTs < msMin || matchTs > msMax) {
        return false;
      }
    }
    return true;
  }


  public void analyzeQueries(HttpServletRequest httpServletRequest) {

    String id = httpServletRequest.getParameter("queryID");

    // Initialize cursors for database results, this cursor is for the results that we get out of
    // each event in the saved query
    MongoCursor<Document> queryCursor = null;

    String queries = httpServletRequest.getParameter("queryIds");
    String queryArray[] = queries.split(",");
    String timeFilter = httpServletRequest.getParameter("timeFilter");
    String timeFilterArray[] = timeFilter.split(",");
    double min = Double.parseDouble(timeFilterArray[0]);
    double max = Double.parseDouble(timeFilterArray[1]);

    // System.out.println("timeline filter is" + timeFilter);

    JSONObject queryTimeFilter = null; // TimeFilter which was specified in the saved query
    String savedQueryName = "";
    List<Query> queryEventList = new ArrayList<>();

    // Loop through all the different Queries to be analyzed
    for (int i = 0; i < queryArray.length; i++) {
      Bson shapeQuery =
          Filters.and(Filters.eq("_id", new ObjectId(queryArray[i])), Filters.exists("shape"));

      // if an event Cascade query is saved (Then only the id of all events are stored in the DB, we
      // have to loop differently)
      Bson eventCascadeQuery =
          Filters.and(Filters.eq("_id", new ObjectId(queryArray[i])), Filters.exists("eventIds"));

      MongoCursor<Document> cursor = MongoDBRestProxy.queryCollection.find(shapeQuery).iterator();
      List<Bson> queryFilters = new ArrayList<Bson>();
      Boolean isEventQuery = true;
      JSONObject playerFilters = new JSONObject(JSONObject.NULL);
      JSONObject teamFilters = new JSONObject(JSONObject.NULL);

      while (cursor.hasNext()) { // Loops through only 1 element, (if it is normal eventQuery)
        // Read the shape details and filters
        String shapeType;
        JSONObject shapeContent, eventFilters, periodFilters, matchFilters, sportFilter;
        JSONObject tFilter = null;

        Document d = cursor.next();
        savedQueryName = d.getString("Name");
        shapeType = d.getString("shape");
        shapeContent = new JSONObject(d.getString("coordinates"));
        tFilter = new JSONObject(d.getString("timeFilter"));

        eventFilters = new JSONObject(d.getString("eventFilters"));
        teamFilters = new JSONObject(d.getString("teamFilters"));
        playerFilters = new JSONObject(d.getString("playerFilters"));
        periodFilters = new JSONObject(d.getString("periodFilters"));
        matchFilters = new JSONObject(d.getString("matchFilters"));
        sportFilter = new JSONObject(d.getString("sportFilter"));

        getFilters(queryFilters, eventFilters, teamFilters, playerFilters, periodFilters,
            matchFilters, sportFilter, tFilter);
        getGeoQuery(queryFilters, shapeType, shapeContent);
      }
      cursor.close();

      MongoCursor<Document> eventCascadeCursor =
          MongoDBRestProxy.queryCollection.find(eventCascadeQuery).iterator();

      while (eventCascadeCursor.hasNext()) { // Loops through 1 Document (if it is event Cascade
                                             // Query)
        isEventQuery = false;
        Document d = eventCascadeCursor.next();
        savedQueryName = d.getString("Name");
        List<String> eventIds = (List<String>) d.get("eventIds");
        for (String eId : eventIds) {
          queryFilters.add(Filters.eq("_id", new ObjectId(eId)));
        }
      }

      /*
       * Switch how the documents get filtered based on how it gets saved in the database
       */
      // System.out.println("query Filters are");
      // System.out.println(queryFilters);
      if (isEventQuery == true) {
        queryCursor = MongoDBRestProxy.eventCollection.find(Filters.and(queryFilters))
            .sort(new BasicDBObject("time", 1)).iterator();
      } else {
        queryCursor = MongoDBRestProxy.eventCollection.find(Filters.or(queryFilters))
            .sort(new BasicDBObject("time", 1)).iterator();
      }

      // Change findIterable to query the DB based on the query parameters
      // findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(queryFilters));

      Query q = new Query(savedQueryName);
      String passDirection = "";
      String matchID = "";
      int kickoffTs = 0;
      // Loops through all Events from that query
      while (queryCursor.hasNext()) {
        Document doc = queryCursor.next();

        if (eventInTimeFilter(min, max, matchID, kickoffTs, doc) == false) {
          continue; // If the event is outside the timeFilter specified in the query Analysis
        }
        System.out.print(savedQueryName);
        String event = doc.getString("type");
        Document additional = (Document) doc.get("additionalInfo");

        switch (event) {
          case "successfulPassEvent":
            if (playerFilters.length() != 0) {
              Iterator<?> keys = playerFilters.keys();
              while (keys.hasNext()) {
                String key = (String) keys.next();
                String pID = playerFilters.get(key).toString();
                List<String> players = (List<String>) doc.get("playerIds");
                if (players.get(0).equals(pID)) {
                  addSuccessfulPassEventsData(q, additional);
                } else { // When the player is the receiver of the ball don't count it. Only when he
                         // is the passer
                }
              }
            } else {
              addSuccessfulPassEventsData(q, additional);
            }
            break;
          case "misplacedPassEvent":
            addMisplacedPassEventsData(q, additional);
            break;
          case "goalEvent":
            q.goalEvents++;
            setShotInfo(q, doc);
            break;
          case "shotOnTargetEvent":
            q.shotOnTargetEvents++;
            setShotInfo(q, doc);
            break;
          case "shotOffTargetEvent":
            q.shotOffTargetEvents++;
            setShotInfo(q, doc);
            break;
          case "interceptionEvent":
            // Here we need to check whether a player made the misplaced pass or the interception
            if (playerFilters.length() != 0) {
              Iterator<?> keys = playerFilters.keys();
              while (keys.hasNext()) {
                String key = (String) keys.next();
                String pID = playerFilters.get(key).toString();
                List<String> players = (List<String>) doc.get("playerIds");
                if (players.get(0).equals(pID) && !players.get(0).equals('0')) { // TODO: check if
                                                                                 // this works, this
                                                                                 // is done for the
                                                                                 // Opta data since
                                                                                 // the first id is
                                                                                 // 0 then it should
                                                                                 // not count.
                  addMisplacedPassEventsData(q, additional);
                } else if (players.get(1).equals(pID)) {
                  q.interceptionEvents++;
                }
              }
            } else if (teamFilters.length() != 0) {
              Iterator<?> keys = teamFilters.keys();
              while (keys.hasNext()) {
                String key = (String) keys.next();
                String tID = teamFilters.get(key).toString();
                List<String> teams = (List<String>) doc.get("teamIds");
                if (teams.get(0).equals(tID)) {
                  addMisplacedPassEventsData(q, additional);
                } else if (teams.get(1).equals(tID)) {
                  q.interceptionEvents++;
                }
              }
            } else {
              q.interceptionEvents++;
              addMisplacedPassEventsData(q, additional);
            }
            break;
          case "clearanceEvent":
            q.clearanceEvents++;
            if (additional.getDouble("length") != null) {
              q.totalClearanceLength += additional.getDouble("length");
            }
            break;
          case "throwinEvent":
            q.throwinEvents++;
            break;
          case "cornerkickEvent":
            q.cornerkickEvents++;
            break;
          case "freekickEvent":
            q.freekickEvents++;
            break;
          default:
            // System.out.println("This Event is not in the QueryAnalysis: " + event);
        }
      }

      if (q.successfulPassEvents != 0) {
        q.avgPassLength = Math.floor((q.totalPassLength / q.successfulPassEvents) * 100) / 100;
        q.avgPassVelocity = Math.floor((q.totalPassVelocity / q.successfulPassEvents) * 100) / 100;
        q.avgPacking = Math.floor((q.totalPacking / q.successfulPassEvents) * 100) / 100;
      }
      if (q.misplacedPassEvents != 0) {
        q.avgMisplacedPassLength =
            Math.floor((q.totalMisplacedPassLength / q.misplacedPassEvents) * 100) / 100;
        q.avgMisplacedPassVelocity =
            Math.floor((q.totalMisplacedPassVelocity / q.misplacedPassEvents) * 100) / 100;

      }
      if (q.totalShots != 0) {
        q.avgShotLength = Math.floor((q.totalShotLength / q.totalShots) * 100) / 100;
        q.avgShotVelocity = Math.floor((q.totalShotVelocity / q.totalShots) * 100) / 100;
      }
      if (q.clearanceEvents != 0) {
        q.avgClearanceLength = Math.floor((q.totalClearanceLength / q.clearanceEvents) * 100) / 100;
      }

      queryEventList.add(q);

      // 1. Ask the Database to get the query parameters, if timeFilter in DB is
      // 2. Ask the Database to get the events from that query, check with timeFilter
      // 3. Create QueryInstance with all its events. The passEvents summed up, Shots summed up,
      // need count from every EventType
      // 4. Do that for all queries
    }
    String json = new Gson().toJson(queryEventList);
    System.out.println(json);

    // 5. Loop through all the instances and count what Event is most common
    // Sort the event Types based on the average number per instance. (Add all passevents up /
    // number of instances)
    // 6. Add the events to the jsonobject if they are not 0. Start with the most popular

    JSONObject jsonObject = new JSONObject();
    jsonObject.append("queries", queries);

    this.content = json;
  }

  public void setShotInfo(Query q, Document doc) {
    q.totalShots++;

    // So we don't get an error if the IceHocket GoalEvent is in a query.
    Bson goalsWithAdditionalnfo = Filters.and(Filters.eq("_id", doc.get("_id")),
        Filters.exists("additionalInfo.length", true));

    FindIterable<Document> findIterable =
        MongoDBRestProxy.eventCollection.find(goalsWithAdditionalnfo);
    for (Document d : findIterable) {
      Document additional = (Document) d.get("additionalInfo");
      double shotLength = additional.getDouble("length");
      q.totalShotLength += shotLength;

      if (q.maxShotLength < shotLength) {
        q.maxShotLength = shotLength;
      }

      if (additional.getDouble("velocity") != null) {
        double shotVelocity = additional.getDouble("velocity");
        q.totalShotVelocity += shotVelocity;
        if (q.maxShotVelocity < shotVelocity) {
          q.maxShotVelocity = shotVelocity;
        }
      }
    }
  }

  public int getGameScore(String matchID, String teamID) {
    // Create bson filter with matchId and Eventtype = goal Event and TeamId = teamId
    /*
     * Get all goals that the players team scored in that match
     */
    int goals = 0;
    Bson teamGoals = Filters.and(Filters.eq("type", "goalEvent"), Filters.eq("teamIds.0", teamID),
        Filters.eq("matchId", matchID));

    FindIterable<Document> findIterable = MongoDBRestProxy.eventCollection.find(teamGoals);
    for (Document doc : findIterable) {
      goals++;
    }
    return goals;
  }

  public int getMatchOutcome(Document d, String pID) {
    int outcome;
    int playerTeamScore;
    int otherTeamScore;
    List<Document> homePlayerList = (List<Document>) d.get("homePlayerIds");
    List<Document> awayPlayerList = (List<Document>) d.get("awayPlayerIds");
    String matchID = d.getString("matchId");
    String playerTeamID;
    String otherTeamID;

    if (homePlayerList.contains(pID)) {
      playerTeamID = d.getString("homeTeamId");
      otherTeamID = d.getString("awayTeamId");
    } else {
      playerTeamID = d.getString("awayTeamId");
      otherTeamID = d.getString("homeTeamId");
    }
    playerTeamScore = getGameScore(matchID, playerTeamID);
    otherTeamScore = getGameScore(matchID, otherTeamID);

    if (playerTeamScore > otherTeamScore) { // Player wins
      outcome = 1;
    } else if (playerTeamScore == otherTeamScore) { // Player draws
      outcome = 2;
    } else { // Player loses
      outcome = 3;
    }
    return outcome;
  }

  public void analyzePlayers(HttpServletRequest httpServletRequest) {
    String players = httpServletRequest.getParameter("players");
    String playersArray[] = players.split(",");
    String parameters = httpServletRequest.getParameter("parameters");
    String parametersArray[] = parameters.split(",");

    FindIterable<Document> findIterable;
    JSONObject playersJson = new JSONObject();

    for (int i = 0; i < playersArray.length; i++) {

      Player p = new Player(playersArray[i]); // Set player ID, not the name, we will get that in
                                              // the client
      // Fill the players with all the values
      String playerID = playersArray[i];

      /*
       * To get all the games a player has played in
       */
      Bson matches =
          Filters.or(Filters.eq("homePlayerIds", playerID), Filters.eq("awayPlayerIds", playerID));

      findIterable = MongoDBRestProxy.matchCollection.find(matches);
      for (Document doc : findIterable) {
        p.gamesPlayed++;
        // Here check if the player won that match getMatchOutcome(doc, playerID)
        int score = getMatchOutcome(doc, playerID);
        switch (score) {
          case 1:
            p.gamesWon++;
            break;
          case 2:
            p.gamesDrawn++;
            break;
          case 3:
            p.gamesLost++;
            break;
          default:
            break;
        }
      }

      /*
       * Get all successful Passes by a Player
       */
      Bson succPasses = Filters.and(Filters.eq("type", "successfulPassEvent"),
          Filters.eq("playerIds.0", playerID));

      findIterable = MongoDBRestProxy.eventCollection.find(succPasses);
      for (Document doc : findIterable) {
        p.successfulPasses++;
        p.totalTouches++;
        Document additional = (Document) doc.get("additionalInfo");
        p.totalPassLength += Double.parseDouble(String.valueOf(additional.get("length")));

        if (additional.get("packing") != null) {
          p.totalPacking += Double.parseDouble(String.valueOf(additional.get("packing")));
        } else {
          p.totalPacking = 0;
        }
        if (additional.get("velocity") != null) {
          p.totalPassVelocity += Double.parseDouble(String.valueOf(additional.get("velocity")));
        } else {
          p.totalPassVelocity = 0;
        }
        if (additional.getString("direction") != null) {
          String passDirection = additional.getString("direction");
          switch (passDirection) {
            case "forward":
              p.forwardPasses++;
              break;
            case "backward":
              p.backwardPasses++;
              break;
            case "left":
              p.leftPasses++;
              break;
            case "right":
              p.rightPasses++;
              break;
          }
        }
        if (Double.parseDouble(String.valueOf(additional.get("length"))) > 30) {
          p.longPasses++;
        } else {
          p.shortPasses++;
        }
      }
      if (p.successfulPasses != 0) {
        p.avgPassLength = Math.floor((p.totalPassLength / p.successfulPasses) * 100) / 100;
        if (p.totalPassVelocity != 0) {
          p.avgPassVelocity = Math.floor((p.totalPassVelocity / p.successfulPasses) * 100) / 100;
        } else {
          p.avgPassVelocity = 0;
        }
        if (p.totalPacking != 0) {
          p.avgPacking = Math.floor((p.totalPacking / p.successfulPasses) * 100) / 100;
        } else {
          p.avgPacking = 0;
        }
      }
      /*
       * Get the failed Passes from the interceptions the player made form the DB. The first player
       * in the interceptions Data made a pass that was intercepted by second player
       */
      Bson misplacedPasses =
          Filters.and(Filters.eq("type", "interceptionEvent"), Filters.eq("playerIds.0", playerID) // to
                                                                                                   // get
                                                                                                   // the
                                                                                                   // first
                                                                                                   // index
                                                                                                   // of
                                                                                                   // the
                                                                                                   // array
          );
      findIterable = MongoDBRestProxy.eventCollection.find(misplacedPasses);
      for (Document doc : findIterable) {
        p.misplacedPasses++;
        p.totalTouches++;
      }
      /*
       * Get all failed Passes by a Player
       */
      Bson failedPasses =
          Filters.and(Filters.eq("type", "misplacedPassEvent"), Filters.eq("playerIds", playerID));
      findIterable = MongoDBRestProxy.eventCollection.find(failedPasses);
      for (Document doc : findIterable) {
        p.misplacedPasses++;
        p.totalTouches++;
      }

      if ((p.successfulPasses + p.misplacedPasses) != 0) {
        p.passAccuracy = Math
            .floor(((double) p.successfulPasses / (double) (p.successfulPasses + p.misplacedPasses))
                * 10000)
            / 100; // To get the percentage just right
      } else {
        p.passAccuracy = 0;
      }

      /*
       * Get all the Shots on Target by a Player
       */
      Bson shotsOnTarget =
          Filters.and(Filters.eq("type", "shotOnTargetEvent"), Filters.eq("playerIds", playerID));
      findIterable = MongoDBRestProxy.eventCollection.find(shotsOnTarget);
      for (Document doc : findIterable) {
        p.totalTouches++;
        p.totalShots++;
        p.shotsOnTarget++;
        Document additional = (Document) doc.get("additionalInfo");
        if (additional.get("length") != null) {
          p.totalShotLength += Double.parseDouble(String.valueOf(additional.getDouble("length")));
        }
        if (additional.get("velocity") != null) {
          p.totalShotVelocity +=
              Double.parseDouble(String.valueOf(additional.getDouble("velocity")));
        }
      }
      /*
       * Get all the Shots off Target by a Player
       */
      Bson shotsOffTarget =
          Filters.and(Filters.eq("type", "shotOffTargetEvent"), Filters.eq("playerIds", playerID));
      findIterable = MongoDBRestProxy.eventCollection.find(shotsOffTarget);
      for (Document doc : findIterable) {
        p.totalTouches++;
        p.totalShots++;
        p.shotsOffTarget++;
        Document additional = (Document) doc.get("additionalInfo");
        if (additional.get("length") != null) {
          p.totalShotLength += Double.parseDouble(String.valueOf(additional.getDouble("length")));
        }
        if (additional.get("velocity") != null) {
          p.totalShotVelocity +=
              Double.parseDouble(String.valueOf(additional.getDouble("velocity")));
        }
      }
      /*
       * Get all the goals by a Player
       */
      Bson goals = Filters.and(Filters.eq("type", "goalEvent"), Filters.eq("playerIds", playerID));
      findIterable = MongoDBRestProxy.eventCollection.find(goals);
      for (Document doc : findIterable) {
        p.totalTouches++;
        p.totalShots++;
        p.goals++;

        // To check if the Event has the required additionalIfno, like in the Ice-hockey we can't
        // make the query because it only has BallPos as additionalInfo in the goalEvent
        Bson goalsWithAdditionalnfo = Filters.and(Filters.eq("_id", doc.get("_id")),
            Filters.exists("additionalInfo.length", true));

        findIterable = MongoDBRestProxy.eventCollection.find(goalsWithAdditionalnfo);
        for (Document d : findIterable) {
          Document additional = (Document) d.get("additionalInfo");
          if (additional.get("length") != null) {
            p.totalShotLength += Double.parseDouble(String.valueOf(additional.getDouble("length")));
          }
          if (additional.get("velocity") != null) {
            p.totalShotVelocity +=
                Double.parseDouble(String.valueOf(additional.getDouble("velocity")));
          }
        }
      }

      if (p.totalShots != 0) {
        p.avgShotLength = Math.floor((p.totalShotLength / (double) p.totalShots) * 100) / 100;
        p.avgShotVelocity = Math.floor((p.totalShotVelocity / (double) p.totalShots) * 100) / 100;
      }

      /*
       * Get all the dribblings by a Player
       */
      Bson dribblings =
          Filters.and(Filters.eq("type", "dribblingStatistics"), Filters.eq("playerIds", playerID));
      findIterable = MongoDBRestProxy.statisticsCollection.find(dribblings);
      for (Document doc : findIterable) {
        p.totalTouches++;
        p.dribblings++;
        // Currently only counting number of dribblings without using additional Info, could change
        // that below.
        Document additional = (Document) doc.get("additionalInfo");
      }

      /*
       * Get the interceptions the player made form the DB
       */
      Bson interceptions =
          Filters.and(Filters.eq("type", "interceptionEvent"), Filters.eq("playerIds.1", playerID) // to
                                                                                                   // get
                                                                                                   // the
                                                                                                   // seconds
                                                                                                   // index
                                                                                                   // of
                                                                                                   // the
                                                                                                   // array
          );
      findIterable = MongoDBRestProxy.eventCollection.find(interceptions);
      for (Document doc : findIterable) {
        p.totalTouches++;
        p.interceptions++;
      }

      /*
       * Get the clearances the player made form the DB
       */
      Bson clearances =
          Filters.and(Filters.eq("type", "clearanceEvent"), Filters.eq("playerIds.0", playerID) // to
                                                                                                // get
                                                                                                // the
                                                                                                // first
                                                                                                // index
                                                                                                // of
                                                                                                // the
                                                                                                // array
          );
      findIterable = MongoDBRestProxy.eventCollection.find(clearances);
      for (Document doc : findIterable) {
        p.totalTouches++;
        p.clearances++;
      }

      /*
       * Get the cornerkicks from the DB
       */
      Bson corners =
          Filters.and(Filters.eq("type", "cornerkickEvent"), Filters.eq("playerIds", playerID));
      findIterable = MongoDBRestProxy.eventCollection.find(corners);
      for (Document doc : findIterable) {
        p.totalTouches++;
        p.cornerkicks++;
      }

      /*
       * Get the throwins from the DB
       */
      Bson throwin =
          Filters.and(Filters.eq("type", "throwinEvent"), Filters.eq("playerIds", playerID));
      findIterable = MongoDBRestProxy.eventCollection.find(throwin);
      for (Document doc : findIterable) {
        p.totalTouches++;
        p.throwins++;
      }

      // System.out.println("avg Pass Length is " + p.avgPassLength);
      // System.out.println("avg Pass Velocity is " + p.avgPassVelocity);
      // System.out.println("avg Packing is " + p.avgPacking);
      // System.out.println("forward passes " + p.forwardPasses);
      // System.out.println("backward Passes " + p.backwardPasses);
      // System.out.println("left " + p.leftPasses);
      // System.out.println("right " + p.rightPasses);
      // System.out.println("pass Accuracy" + p.passAccuracy);

      JSONArray playerStats = new JSONArray();

      for (int j = 0; j < parametersArray.length; j++) {
        switch (parametersArray[j]) {
          case "gamesPlayed":
            playerStats.put(p.gamesPlayed);
            break;
          case "gamesWon":
            playerStats.put(p.gamesWon);
            break;
          case "gamesLost":
            playerStats.put(p.gamesLost);
            break;
          case "gamesDrawn":
            playerStats.put(p.gamesDrawn);
            break;
          case "winPercentage":
            if (p.gamesPlayed != 0) { // To avoid error if he hasn't played any
              p.winPercentage = (double) p.gamesWon / ((double) p.gamesPlayed);
            }
            playerStats.put(p.winPercentage);
            break;
          case "successfulPassEvent":
            playerStats.put(p.successfulPasses);
            break;
          case "misplacedPassEvent":
            playerStats.put(p.misplacedPasses);
            break;
          case "passAccuracy":
            playerStats.put(p.passAccuracy);
            break;
          case "shortPasses":
            playerStats.put(p.shortPasses);
            break;
          case "longPasses":
            playerStats.put(p.longPasses);
            break;
          case "leftPasses":
            playerStats.put(p.leftPasses);
            break;
          case "rightPasses":
            playerStats.put(p.rightPasses);
            break;
          case "forwardPasses":
            playerStats.put(p.forwardPasses);
            break;
          case "backwardPasses":
            playerStats.put(p.backwardPasses);
            break;
          case "avgPassLength":
            playerStats.put(p.avgPassLength);
            break;
          case "avgPassVelocity":
            playerStats.put(p.avgPassVelocity);
            break;
          case "avgPacking":
            playerStats.put(p.avgPacking);
            break;
          case "goalEvent":
            playerStats.put(p.goals);
            break;
          case "totalShots":
            playerStats.put(p.totalShots);
            break;
          case "shotOnTargetEvent":
            playerStats.put(p.shotsOnTarget);
            break;
          case "shotOffTargetEvent":
            playerStats.put(p.shotsOffTarget);
            break;
          case "avgShotVelocity":
            playerStats.put(p.avgShotVelocity);
            break;
          case "avgShotLength":
            playerStats.put(p.avgShotLength);
            break;
          case "DribblingStatistic":
            playerStats.put(p.dribblings);
            break;
          case "interceptionEvent":
            playerStats.put(p.interceptions); // Put info in misplaced passes
            break;
          case "clearanceEvent":
            playerStats.put(p.clearances);
            break;
          case "totalTouches": // get count all events + dribblingStatistics
            playerStats.put(p.totalTouches);
            break;
          case "cornerkickEvent":
            playerStats.put(p.cornerkicks);
            break;
          case "throwinEvent":
            playerStats.put(p.throwins);
            break;
          default:
            break;
        }
      }
      playersJson.append(playersArray[i], playerStats);
    }

    // System.out.println(playersJson);
    this.content = playersJson.toString();
  }

  /**
   * Gets the saved filter ID, then takes the attributes from the save dFilter DB, then runs the
   * getAreEvent query directly from these attributes and gives the requested events back
   */
  public void rerunQuery(HttpServletRequest httpServletRequest) {

    // Get the parameters of the saved Query with all the elements of it by search for by MongoID
    // object

    String id = httpServletRequest.getParameter("queryID");
    // For the normal AreaEvent Query that is saved
    Bson shapeQuery = Filters.and(Filters.eq("_id", new ObjectId(id)), Filters.exists("shape"));

    // if an event Cascade query is saved (Then only the id of all events are stored in the DB, we
    // have to loop differently)
    Bson eventCascadeQuery =
        Filters.and(Filters.eq("_id", new ObjectId(id)), Filters.exists("eventIds"));

    MongoCursor<Document> cursor = MongoDBRestProxy.queryCollection.find(shapeQuery).iterator();
    List<Bson> queryFilters = new ArrayList<Bson>();

    Boolean isEventQuery = true;
    while (cursor.hasNext()) { // Loops through only 1 element, (if it is normal eventQuery)
      // Read the shape details and filters
      String shapeType;
      JSONObject shapeContent, eventFilters, playerFilters, periodFilters, matchFilters,
          sportFilter, teamFilters;
      JSONObject timeFilter = null;

      Document d = cursor.next();
      shapeType = d.getString("shape");
      shapeContent = new JSONObject(d.getString("coordinates"));
      timeFilter = new JSONObject(d.getString("timeFilter"));

      eventFilters = new JSONObject(d.getString("eventFilters"));
      teamFilters = new JSONObject(d.getString("teamFilters"));
      playerFilters = new JSONObject(d.getString("playerFilters"));
      periodFilters = new JSONObject(d.getString("periodFilters"));
      matchFilters = new JSONObject(d.getString("matchFilters"));
      sportFilter = new JSONObject(d.getString("sportFilter"));

      getFilters(queryFilters, eventFilters, teamFilters, playerFilters, periodFilters,
          matchFilters, sportFilter, timeFilter);
      getGeoQuery(queryFilters, shapeType, shapeContent);
    }
    cursor.close();

    MongoCursor<Document> eventCascadeCursor =
        MongoDBRestProxy.queryCollection.find(eventCascadeQuery).iterator();

    while (eventCascadeCursor.hasNext()) { // Loops through 1 Document (if it is event Cascade
                                           // Query)
      isEventQuery = false;
      Document d = eventCascadeCursor.next();
      List<String> eventIds = (List<String>) d.get("eventIds");
      for (String eId : eventIds) {
        queryFilters.add(Filters.eq("_id", new ObjectId(eId)));
      }
    }

    // Initialize cursors for database results, this cursor is for the results that we get out of
    // each event in the saved query
    MongoCursor<Document> queryCursor = null;

    /*
     * Switch how the documents get filtered based on how it gets saved in the database
     */
    if (isEventQuery == true) {
      queryCursor = MongoDBRestProxy.eventCollection.find(Filters.and(queryFilters))
          .sort(new BasicDBObject("time", 1)).iterator();
    } else {
      queryCursor = MongoDBRestProxy.eventCollection.find(Filters.or(queryFilters))
          .sort(new BasicDBObject("time", 1)).iterator(); // This is when the IDs of the events are
                                                          // stored in the savedFilters Collection.
    }

    /*
     * Query the database for the events
     */
    JSONObject jsonObj = null;
    JSONObject resultJsonObj = null;
    JSONArray finalResultArray = new JSONArray();
    JSONArray finalVideoArray = new JSONArray();
    JSONObject finalResult = new JSONObject();
    /*
     * Loop through database result to form JSON string for web result
     */
    try {
      while (queryCursor.hasNext()) {

        jsonObj = new JSONObject(queryCursor.next().toJson());

        resultJsonObj = new JSONObject();

        resultJsonObj.append("details", jsonObj);
        finalResultArray.put(resultJsonObj);
      }
    } finally {
      /*
       * Close the cursors
       */
      queryCursor.close();

      /*
       * Create final result to be sent back to client
       */
      finalResult.append("videoDetails", finalVideoArray);
      finalResult.append("result", finalResultArray);
    }

    /*
     * Set content for HttpResponse
     */
    this.content = finalResult.toString();
  }



  /**
   * This function returns all player names that are in the mongodb and their id.
   * 
   * @param httpServletRequest
   */
  public void getPlayers(HttpServletRequest httpServletRequest) {
    // helper Class to create a temporary list

    // List<NameID> players = new ArrayList<>();
    // Use HashMap and set the playerID as key do eliminate all duplicates and for faster iteration.
    HashMap<String, String> players = new HashMap<>();
    MongoCursor<Document> cursor = MongoDBRestProxy.matchCollection.find().iterator();

    // get all match data and create a list of players and their IDs with no
    // duplicates
    while (cursor.hasNext()) {
      Document d = cursor.next();
      String a = d.get("awayPlayerNames").toString();
      String aID = d.get("awayPlayerIds").toString();
      String h = d.get("homePlayerNames").toString();
      String hID = d.get("homePlayerIds").toString();

      String[] aPlayers = a.substring(1, a.length() - 1).split(", ");
      String[] aPlayersID = aID.substring(1, aID.length() - 1).split(", ");
      String[] hPlayers = h.substring(1, h.length() - 1).split(", ");
      String[] hPlayersID = hID.substring(1, hID.length() - 1).split(", ");

      for (int i = 0; i < hPlayers.length; i++) {
        players.put(hPlayersID[i], hPlayers[i]);
      }
      for (int i = 0; i < aPlayers.length; i++) {
        players.put(aPlayersID[i], aPlayers[i]);
      }
    }

    JSONObject jsonObj = null;
    JSONObject resultJsonObj = new JSONObject();

    Iterator it = players.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry pair = (Map.Entry) it.next();
      jsonObj = new JSONObject();
      jsonObj.put("pid", pair.getKey());
      jsonObj.put("name", pair.getValue());
      resultJsonObj.append("result", jsonObj);
    }

    cursor.close();
    this.content = resultJsonObj.toString();
  }

  public void saveEventCascade(HttpServletRequest httpServletRequest) {
    String eIds = httpServletRequest.getParameter("eventIds");
    List<String> eventIds = Arrays.asList(eIds.split("\\s*,\\s*")); // to take the eventIs and make
                                                                    // them into a List

    MongoCollection<Document> savedCol = MongoDBRestProxy.db.getCollection("savedFilters");

    Document document = new Document("Name", httpServletRequest.getParameter("queryName"))
        .append("eventIds", eventIds);
    savedCol.insertOne(document);

    Object id = document.get("_id");
    JSONObject jsonObj = new JSONObject();
    jsonObj.append("id", id);
    this.content = jsonObj.toString();
  }

  public void saveFilter(HttpServletRequest httpServletRequest) {
    MongoCollection<Document> savedCol = MongoDBRestProxy.db.getCollection("savedFilters");

    Document document = new Document("Name", httpServletRequest.getParameter("filterName"))
        .append("shape", httpServletRequest.getParameter("shape"))
        .append("coordinates", httpServletRequest.getParameter("coordinates"))
        .append("eventFilters", httpServletRequest.getParameter("eventFilters"))
        .append("teamFilters", httpServletRequest.getParameter("teamFilters"))
        .append("playerFilters", httpServletRequest.getParameter("playerFilters"))
        .append("periodFilters", httpServletRequest.getParameter("periodFilters"))
        .append("timeFilter", httpServletRequest.getParameter("timeFilter"))
        .append("sportFilter", httpServletRequest.getParameter("sportFilter"))
        .append("matchFilters", httpServletRequest.getParameter("matchFilters"));
    savedCol.insertOne(document);

    // System.out.println("Filtername is " + httpServletRequest.getParameter("filterName"));
    // System.out.println("Shape is " + httpServletRequest.getParameter("shape"));
    // System.out.println("Coordinates are " + httpServletRequest.getParameter("coordinates"));
    // System.out.println("Save Filter is" + httpServletRequest);

    Object id = document.get("_id"); // Get the ID of the just inserted document
    JSONObject jsonObj = new JSONObject();
    jsonObj.append("id", id);
    this.content = jsonObj.toString(); // It needs to be a jsonObj, otherwise the response is not
                                       // sent
  }

  public void getMatches(HttpServletRequest httpServletRequest) {
    MongoCursor<Document> cursor = MongoDBRestProxy.matchCollection.find().iterator();

    JSONObject jsonObj = new JSONObject();
    int counter = 0;
    while (cursor.hasNext()) {
      Document d = cursor.next();
      JSONObject j = new JSONObject();
      j.append("matchId", d.get("matchId"));
      j.append("videoPath", d.get("videoPath"));
      j.append("sport", d.get("sport"));
      jsonObj.append("match" + counter, j);
      counter++;
    }
    this.content = jsonObj.toString();
  }
}


/*
 * Helper class for creating name + id lists
 */
class NameID {
  String name;
  String id;

  NameID(String name, String id) {
    this.name = name;
    this.id = id;
  }

  public String toString() {
    return name + " " + id;
  }

  @Override
  public boolean equals(Object other) {
    if (other == null)
      return false;
    if (other == this)
      return true;
    if (!(other instanceof NameID))
      return false;
    NameID othernid = (NameID) other;
    if (othernid.name.equals(this.name) && othernid.id == this.id)
      return true;
    return false;
  }
}


/**
 * Helper class for the query Analysis
 */
class Query {
  String name;

  int clearanceEvents;
  transient double totalClearanceLength;
  double avgClearanceLength;
  int cornerkickEvents;
  int freekickEvents;
  int interceptionEvents;
  int misplacedPassEvents;
  int successfulPassEvents;
  int forwardPasses;
  int backwardPasses;
  int leftPasses;
  int rightPasses;
  int overThirtyPasses;
  int fifteenToThirtyPasses;
  int sevenToFifteenPasses;
  int underSevenPasses;
  int MPoverThirtyPasses;
  int MPfifteenToThirtyPasses;
  int MPsevenToFifteenPasses;
  int MPunderSevenPasses;
  transient double totalPassLength;
  double avgPassLength;
  transient double totalPassVelocity;
  double avgPassVelocity;
  transient double totalPacking;
  double avgPacking;
  transient double totalMisplacedPassLength;
  double avgMisplacedPassLength;
  transient double totalMisplacedPassVelocity;
  double avgMisplacedPassVelocity;
  int misplacedPassForward;
  int misplacedPassBackward;
  int misplacedPassLeft;
  int misplacedPassRight;
  int shotOffTargetEvents;
  transient double totalShotLength;
  double avgShotLength;
  transient double totalShotVelocity;
  double avgShotVelocity;
  int shotOnTargetEvents;
  int goalEvents;
  int throwinEvents;
  double maxPassLength;
  double maxPassVelocity;
  double maxShotLength;
  double maxShotVelocity;
  int totalShots;
  double maxMisplacedPassLength;
  double maxMisplacedPassVelocity;

  public Query(String name) {
    this.name = name;

    this.clearanceEvents = 0;
    this.totalClearanceLength = 0;
    this.avgClearanceLength = 0;
    this.cornerkickEvents = 0;
    this.freekickEvents = 0;
    this.interceptionEvents = 0;
    this.misplacedPassEvents = 0;
    this.successfulPassEvents = 0;
    this.forwardPasses = 0;
    this.backwardPasses = 0;
    this.leftPasses = 0;
    this.rightPasses = 0;
    this.totalPassLength = 0;
    this.avgPassLength = 0;
    this.totalPassVelocity = 0;
    this.avgPassVelocity = 0;
    this.totalPacking = 0;
    this.avgPacking = 0;
    this.shotOffTargetEvents = 0;
    this.totalShotLength = 0;
    this.avgShotLength = 0;
    this.totalShotVelocity = 0;
    this.avgShotVelocity = 0;
    this.shotOnTargetEvents = 0;
    this.goalEvents = 0;
    this.throwinEvents = 0;
    this.underSevenPasses = 0;
    this.overThirtyPasses = 0;
    this.totalMisplacedPassLength = 0;
    this.avgMisplacedPassLength = 0;
    this.totalMisplacedPassVelocity = 0;
    this.avgMisplacedPassVelocity = 0;
    this.misplacedPassBackward = 0;
    this.misplacedPassForward = 0;
    this.misplacedPassLeft = 0;
    this.misplacedPassRight = 0;
    this.maxPassLength = 0;
    this.maxShotLength = 0;
    this.maxPassVelocity = 0;
    this.maxShotVelocity = 0;
    this.totalShots = 0;
    this.maxMisplacedPassLength = 0;
    this.maxMisplacedPassVelocity = 0;
    this.sevenToFifteenPasses = 0;
    this.fifteenToThirtyPasses = 0;
    this.MPfifteenToThirtyPasses = 0;
    this.MPoverThirtyPasses = 0;
    this.MPsevenToFifteenPasses = 0;
    this.MPunderSevenPasses = 0;
  }
}


/**
 * Helper class for the player Analysis
 */
class Player {
  String id;

  int gamesPlayed;
  int gamesWon;
  int gamesLost;
  int gamesDrawn;
  double winPercentage;
  int misplacedPasses;
  int successfulPasses;
  double passAccuracy;
  int shortPasses;
  int longPasses;
  double totalPassLength;
  double avgPassLength;
  double totalPassVelocity;
  double avgPassVelocity;
  double totalPacking;
  double avgPacking;
  int forwardPasses;
  int backwardPasses;
  int leftPasses;
  int rightPasses;
  int goals;
  int totalShots;
  int shotsOnTarget;
  int shotsOffTarget;
  double totalShotVelocity;
  double avgShotVelocity;
  double totalShotLength;
  double avgShotLength;
  int dribblings;
  int interceptions;
  int clearances;
  int totalTouches;
  int cornerkicks;
  int throwins;


  public Player(String id) {
    this.id = id;

    this.gamesPlayed = 0;
    this.gamesWon = 0;
    this.gamesLost = 0;
    this.gamesDrawn = 0;
    this.winPercentage = 0;
    this.misplacedPasses = 0;
    this.successfulPasses = 0;
    this.passAccuracy = 0;
    this.shortPasses = 0;
    this.longPasses = 0;
    this.totalPassLength = 0;
    this.avgPassLength = 0;
    this.totalPassVelocity = 0;
    this.avgPassVelocity = 0;
    this.totalPacking = 0;
    this.avgPacking = 0;
    this.forwardPasses = 0;
    this.backwardPasses = 0;
    this.leftPasses = 0;
    this.rightPasses = 0;
    this.goals = 0;
    this.totalShots = 0;
    this.shotsOnTarget = 0;
    this.shotsOffTarget = 0;
    this.totalShotVelocity = 0;
    this.avgShotVelocity = 0;
    this.totalShotLength = 0;
    this.avgShotLength = 0;
    this.dribblings = 0;
    this.interceptions = 0;
    this.clearances = 0;
    this.totalTouches = 0;
    this.cornerkicks = 0;
    this.throwins = 0;
  }
}
