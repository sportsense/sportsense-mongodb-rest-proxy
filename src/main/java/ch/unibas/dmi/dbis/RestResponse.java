/*
 * SportSense Copyright (C) 2019 University of Basel
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package ch.unibas.dmi.dbis;

import static com.mongodb.client.model.Projections.excludeId;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestResponse {
  /**
   * Slf4j logger
   */
  private static final Logger logger = LoggerFactory.getLogger(RestResponse.class);
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


  /**
   * This function starts a region query. The function checks all the filters, goes through the
   * eventCollection, and returns the final result back to the client.
   */
  public void getAreaEvent(HttpServletRequest request) {
    List<Bson> queryFilters = new ArrayList<Bson>();
    getFilters(queryFilters, request);

    // Read the shape details and filters
    String shapeType;
    JSONObject shapeContent;

    if (request.getParameterMap().containsKey("shape")) {
      shapeType = request.getParameter("shape");
      shapeContent = new JSONObject(request.getParameter("coordinates"));
    } else {
      shapeType = null;
      shapeContent = null;
    }

    if (request.getParameterMap().containsKey("sportFilter")) {
      MongoCursor<Document> matchSport =
          MongoDBRestProxy.matchCollection.find().sort(new BasicDBObject("time", 1)).allowDiskUse(true).iterator();

      Document filterHelper;
      String sport;
      String sportFilter;
      while (matchSport.hasNext()) {
        filterHelper = matchSport.next();
        sport = filterHelper.getString("sport");
        sportFilter = request.getParameter("sportFilter");
        if (!sportFilter.contains(sport)) {
          queryFilters.add(Filters.ne("matchId", filterHelper.getString("matchId")));
        }
      }
    }

    if (request.getParameterMap().containsKey("periodFilters")) {
      String periodFilters = request.getParameter("periodFilters");
      String sportFilter = request.getParameter("sportFilter");
      if (sportFilter.contains("football")) {
        if (periodFilters.contains(":S") && !periodFilters.contains(":F")) {
          queryFilters.add(Filters.gt("ts", 2700000));
        }
        if (periodFilters.contains(":F") && !periodFilters.contains(":S")) {
          queryFilters.add(Filters.lt("ts", 2700000));
        }
      }
      if (sportFilter.contains("icehockey")) {
        if (periodFilters.contains(":F") && !periodFilters.contains(":S")
            && !periodFilters.contains(":T")) {
          queryFilters.add(Filters.lt("ts", 1200000));
        }
        if (periodFilters.contains(":S") && !periodFilters.contains(":F")
            && !periodFilters.contains(":T")) {
          queryFilters.add(Filters.and(Filters.gt("ts", 1200000), Filters.lt("ts", 2400000)));
        }
        if (periodFilters.contains(":T") && !periodFilters.contains(":F")
            && !periodFilters.contains(":S")) {
          queryFilters.add(Filters.gt("ts", 2400000));
        }
        if (periodFilters.contains(":F") && periodFilters.contains(":S")
            && !periodFilters.contains(":T")) {
          queryFilters.add(Filters.lt("ts", 2400000));
        }
        if (periodFilters.contains(":F") && !periodFilters.contains(":S")
            && periodFilters.contains(":T")) {
          queryFilters.add(Filters.or(Filters.gt("ts", 2400000), Filters.lt("ts", 1200000)));

        }
        if (!periodFilters.contains(":F") && periodFilters.contains(":S")
            && periodFilters.contains(":T")) {
          queryFilters.add(Filters.gt("ts", 1200000));
        }
      }
    }
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

    MongoCursor<Document> cursor = MongoDBRestProxy.eventCollection.find(Filters.and(queryFilters))
        .sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();

    JSONObject jsonObj = null;
    JSONObject resultJsonObj = null;
    JSONArray finalResultArray = new JSONArray();
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
      }

    } finally {
      /*
       * Close the cursors
       */
      cursor.close();

      /*
       * Create final result to be sent back to client
       */
      finalResult.append("result", finalResultArray);
    }

    /*
     * Set content for HttpResponse
     */
    this.content = finalResult.toString();
  }


  /**
   * This function starts a motion path query.
   */
  public void getMotionPath(HttpServletRequest request) {
    // Construct query filter
    List<Bson> queryFilters = new ArrayList<Bson>();
    String shapeType = request.getParameter("shape");

    // matchIDFilter only contained in Offball-MP requests
    if (request.getParameterMap().containsKey("matchIDFilter")) {
      String matchID = request.getParameter("matchIDFilter");
      queryFilters.add(Filters.eq("matchId", matchID));
    }

    FindIterable<Document> matchData = MongoDBRestProxy.matchCollection.find();

    if (request.getParameterMap().containsKey("sportFilter")) {
      String sportFilter = request.getParameter("sportFilter");

      for (Document matchFilter : matchData) {
        String sport = (String) matchFilter.get("sport");
        if (!sportFilter.contains(sport)) {
          String matchId = (String) matchFilter.get("matchId");
          queryFilters.add(Filters.ne("matchId", matchId));
        }
      }
    }

    JSONObject shapeContent = new JSONObject(request.getParameter("coordinates"));
    JSONObject playerFilters = new JSONObject(request.getParameter("playerFilters"));

    getFilters(queryFilters, request);
    getGeoQuery(queryFilters, shapeType, shapeContent);

    queryFilters.add(Filters.and(Filters.eq("type", "fieldObjectState")));

    // Parameters
    int maxTsDiff = 1000;
    double minPathLength = 0.0;

    boolean requireMinPathLength = false;

    // Check Freehand/Straight MP vs. Offball MPs
    if (request.getParameter("minPathLength") != null) {
      // OffBall MP
      minPathLength = 2;
    } else {
      requireMinPathLength = true;
      minPathLength = 20.0;
    }

    // Perform MongoDB query and sort by (ts and) matchId
    FindIterable<Document> trackingData = MongoDBRestProxy.statesCollection
        .find(Filters.and(queryFilters)).sort(new BasicDBObject("matchId", 1).append("ts", 1)).allowDiskUse(true);

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
          // list (or if a we reach a new matchId in the trackingData list)
          firstItem = false;
          curPathMatchId = curMatchId;
          mpSetUpPathObject(curPathJsonObj, curMatchId, curId, curTs, curVideoTs, curPlayerId);
        }

        // Check if the path should be ended due to data inconsistencies or time differences
        boolean continuePath = true;

        if (!curPathMatchId.equals(curMatchId)) { // path is not in the same match
          // because data are sorted by matchId: a new matchId means a new first Item is needed
          firstItem = true;
          // this avoids continuing and generating wrong results
          continuePath = false;
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
   * This function starts an event cascade. The function checks if this is a forward or a backward
   * event cascade and executes the corresponding function.
   */
  public void getEventCascade(HttpServletRequest request) {
    List<Bson> queryFilters = new ArrayList<Bson>();
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

      List<Bson> tmp = new ArrayList<Bson>(queryFilters);
      tmp.add(b[i]);

      // This leads to an increase in performance of Reverse Event Cascades
      if (reverse.equals("true")) {
        tmp.add(Filters.eq("matchId", matchIds[i]));
      }
      // These events happen too frequently though they should not be considered in Event Cascades
      tmp.add(Filters.ne("type", "areaEvent"));
      tmp.add(Filters.ne("type", "ballPossessionChangeEvent"));
      tmp.add(Filters.ne("type", "dribblingEvent"));
      tmp.add(Filters.ne("type", "duelEvent"));
      tmp.add(Filters.ne("type", "kickEvent"));
      tmp.add(Filters.ne("type", "matchTimeProgressEvent"));
      tmp.add(Filters.ne("type", "speedLevelChangeEvent"));
      tmp.add(Filters.ne("type", "underPressureEvent"));
      // These are OPTA events and should also not be considered in Event Cascades
      tmp.add(Filters.ne("type", "playerOffEvent"));
      tmp.add(Filters.ne("type", "playerOnEvent"));

      try {
        cursor = MongoDBRestProxy.eventCollection.find(Filters.and(tmp))
            .sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();

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
  }


  /**
   * This function adds filters to the bson list in order to find events within a certain area AND
   * within a certain time slot. Because this is a forward event cascade the time slot is right
   * after the searched time (time stamp + the threshold value).
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
   * This function returns all event types that are in the mongodb.
   * events.
   */
  public void getEventTypes(HttpServletRequest httpServletRequest) {
    List<Bson> matchFilters = new ArrayList<Bson>();
    String sportFilter = httpServletRequest.getParameter("sportFilter");

    MongoCursor<Document> matches = MongoDBRestProxy.matchCollection.find().iterator();
    ArrayList<String> notWantedMatchList = new ArrayList<>();

    while (matches.hasNext()) {
      Document match = matches.next();
      if (!match.get("sport").toString().equals(sportFilter)) {
        matchFilters.add(Filters.ne("matchId", match.get("matchId").toString()));
      }
    }
    
    MongoCursor<Document> cursor;
    if(matchFilters.isEmpty()) {
      cursor = MongoDBRestProxy.eventCollection.find().iterator();
    }
    else
      cursor = MongoDBRestProxy.eventCollection.find(Filters.and(matchFilters)).iterator();

    // TODO: Remove the unwanted Events by adding them to the list
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
    ArrayList<String> wantedEventList = new ArrayList<>();
    while (cursor.hasNext()) {
      Document event = cursor.next();
      if (event.containsKey("type")) {
        if (!notWantedEventList.contains(event.get("type").toString())
            && !wantedEventList.contains(event.get("type").toString())) {
          wantedEventList.add(event.get("type").toString());
        }
      }
    }

    for (int i = 0; i < wantedEventList.size(); i++) {
      jsonObj.append("eventType", wantedEventList.get(i));
    }
    this.content = jsonObj.toString();
  }


  /**
   * This function returns all player names that are in the mongodb and their id.
   */
  public void getPlayers(HttpServletRequest httpServletRequest) {
    // helper Class to create a temporary list

    // List<NameID> players = new ArrayList<>();
    // Use HashMap and set the playerID as key do eliminate all duplicates and for faster iteration.
    HashMap<String, Map.Entry<String, String>> players = new HashMap<>();
    MongoCursor<Document> cursor;
    String sportFilter = httpServletRequest.getParameter("sportFilter");

    if (httpServletRequest.getParameter("matchFilters") != null) {
      JSONObject matchFilter = new JSONObject(httpServletRequest.getParameter("matchFilters"));
      String matchId = matchFilter.getString("match0");
      List<Bson> queryFilter = new ArrayList<Bson>();
      queryFilter.add(Filters.eq("matchId", matchId));
      cursor = MongoDBRestProxy.matchCollection.find(Filters.and(queryFilter)).iterator();
    } else {
      cursor = MongoDBRestProxy.matchCollection.find().iterator();
    }

    // get all match data and create a list of players and their IDs with no
    // duplicates
    while (cursor.hasNext()) {
      Document d = cursor.next();
      String matchSport = d.get("sport").toString();

      if (httpServletRequest.getParameterMap().containsKey("sportFilter")) {
        if (matchSport.equals(sportFilter)) {
          String a = d.get("awayPlayerNames").toString();
          String aID = d.get("awayPlayerIds").toString();
          String h = d.get("homePlayerNames").toString();
          String hID = d.get("homePlayerIds").toString();

          String[] aPlayers = a.substring(1, a.length() - 1).split(", ");
          String[] aPlayersID = aID.substring(1, aID.length() - 1).split(", ");
          String[] hPlayers = h.substring(1, h.length() - 1).split(", ");
          String[] hPlayersID = hID.substring(1, hID.length() - 1).split(", ");
          for (int i = 0; i < hPlayersID.length; i++) {
            players.put(hPlayersID[i], new AbstractMap.SimpleEntry<>(hPlayers[i], matchSport));
          }
          for (int i = 0; i < aPlayersID.length; i++) {
            players.put(aPlayersID[i], new AbstractMap.SimpleEntry<>(aPlayers[i], matchSport));
          }
        }
      } else {
        String a = d.get("awayPlayerNames").toString();
        String aID = d.get("awayPlayerIds").toString();
        String h = d.get("homePlayerNames").toString();
        String hID = d.get("homePlayerIds").toString();

        String[] aPlayers = a.substring(1, a.length() - 1).split(", ");
        String[] aPlayersID = aID.substring(1, aID.length() - 1).split(", ");
        String[] hPlayers = h.substring(1, h.length() - 1).split(", ");
        String[] hPlayersID = hID.substring(1, hID.length() - 1).split(", ");
        for (int i = 0; i < hPlayers.length; i++) {
          players.put(hPlayersID[i], new AbstractMap.SimpleEntry<>(hPlayers[i], matchSport));
        }
        for (int i = 0; i < aPlayers.length; i++) {
          players.put(aPlayersID[i], new AbstractMap.SimpleEntry<>(aPlayers[i], matchSport));
        }
      }
    }

    JSONObject jsonObj = null;
    JSONObject resultJsonObj = new JSONObject();

    Iterator it = players.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry pair = (Map.Entry) it.next();
      jsonObj = new JSONObject();
      jsonObj.put("pid", pair.getKey());
      Map.Entry entry = (Map.Entry) pair.getValue();
      jsonObj.put("name", entry.getKey());
      jsonObj.put("sport", entry.getValue());
      resultJsonObj.append("result", jsonObj);
    }

    cursor.close();
    this.content = resultJsonObj.toString();
  }


  /**
   * This function returns all team names that are in the mongodb and their ids.
   */
  public void getTeams(HttpServletRequest httpServletRequest) {
    MongoCursor<Document> cursor;

    if (httpServletRequest.getParameter("matchFilters") != null) {
      JSONObject matchFilter = new JSONObject(httpServletRequest.getParameter("matchFilters"));
      String matchId = matchFilter.getString("match0");
      List<Bson> queryFilter = new ArrayList<Bson>();
      queryFilter.add(Filters.eq("matchId", matchId));
      cursor = MongoDBRestProxy.matchCollection.find(Filters.and(queryFilter)).iterator();
    } else {
      cursor = MongoDBRestProxy.matchCollection.find().iterator();
    }

    Set<NameID> teamnames = new TreeSet<NameID>(Comparator.comparing(nameId -> nameId.id));

    String sportFilter = httpServletRequest.getParameter("sportFilter");
    String sport;
    // get all match data and create a list of players and their IDs with no
    // duplicates
    while (cursor.hasNext()) {
      Document d = cursor.next();
      sport = d.get("sport").toString();

      if (httpServletRequest.getParameterMap().containsKey("sportFilter")) {
        if (sportFilter.contains(sport)) {
          String a = d.get("awayTeamName").toString();
          String aID = d.get("awayTeamId").toString();
          String h = d.get("homeTeamName").toString();
          String hID = d.get("homeTeamId").toString();

          NameID hnid = new NameID(h, sport, hID);
          NameID anid = new NameID(a, sport, aID);

          if (!teamnames.contains(hnid)) {
            teamnames.add(hnid);
          }
          if (!teamnames.contains(anid)) {
            teamnames.add(anid);
          }
        }
      } else {
        String a = d.get("awayTeamName").toString();
        String aID = d.get("awayTeamId").toString();
        String h = d.get("homeTeamName").toString();
        String hID = d.get("homeTeamId").toString();

        NameID hnid = new NameID(h, sport, hID);
        NameID anid = new NameID(a, sport, aID);

        if (!teamnames.contains(hnid)) {
          teamnames.add(hnid);
        }
        if (!teamnames.contains(anid)) {
          teamnames.add(anid);
        }
      }
    }

    JSONObject resultJsonObj = new JSONObject();

    for (NameID t : teamnames) {
      JSONObject jsonObj = new JSONObject();
      jsonObj.put("tid", t.id);
      jsonObj.put("name", t.name);
      jsonObj.put("teamSport", t.sport);
      resultJsonObj.append("result", jsonObj);
    }

    cursor.close();
    this.content = resultJsonObj.toString();
  }


  public void getMatches(HttpServletRequest httpServletRequest) {
    MongoCursor<Document> cursor = MongoDBRestProxy.matchCollection.find().iterator();
    String sportFilter = httpServletRequest.getParameter("sportFilter");
    JSONObject jsonObj = new JSONObject();
    int counter = 0;

    if (httpServletRequest.getParameterMap().containsKey("sportFilter")) {
      while (cursor.hasNext()) {
        Document d = cursor.next();
        if (d.get("sport").equals(sportFilter)) {
          JSONObject j = new JSONObject();
          j.append("matchId", d.get("matchId"));
          j.append("videoPath", d.get("videoPath"));
          j.append("sport", d.get("sport"));
          j.append("homeTeamName", d.get("homeTeamName"));
          j.append("awayTeamName", d.get("awayTeamName"));
          jsonObj.append("match" + counter, j);
          counter++;
        }
      }
    } else {
      while (cursor.hasNext()) {
        Document d = cursor.next();
        if (d.get("sport").equals(sportFilter)) {
          JSONObject j = new JSONObject();
          j.append("matchId", d.get("matchId"));
          j.append("videoPath", d.get("videoPath"));
          j.append("sport", d.get("sport"));
          j.append("homeTeamName", d.get("homeTeamName"));
          j.append("awayTeamName", d.get("awayTeamName"));
          jsonObj.append("match" + counter, j);
          counter++;
        }
      }
    }
    this.content = jsonObj.toString();
  }


  public void getUsers(HttpServletRequest httpServletRequest) {
    MongoCursor<Document> cursor = MongoDBRestProxy.usersCollection.find().iterator();

    JSONObject jsonObj = new JSONObject();
    int counter = 0;
    while (cursor.hasNext()) {
      Document d = cursor.next();
      JSONObject j = new JSONObject();
      j.put("userRole", d.getString("role"));
      j.put("userName", d.getString("userName"));
      j.put("pressingIndexThreshold", d.getDouble("pressingIndexThreshold"));
      j.put("pressingDurationThreshold", d.getInteger("pressingDurationThreshold"));
      jsonObj.append("user" + counter, j);
      counter++;
    }
    this.content = jsonObj.toString();
  }


  public void getUserParameter(HttpServletRequest httpServletRequest) {
    MongoCursor<Document> cursor = MongoDBRestProxy.usersCollection.find().iterator();
    String userName = String.valueOf(httpServletRequest.getParameter("userName"));

    JSONObject jsonObj = new JSONObject();

    while (cursor.hasNext()) {
      Document d = cursor.next();
      if (d.getString("userName").equals(userName)) {
        JSONObject j = new JSONObject();
        j.put("userRole", d.getString("role"));
        j.put("userName", d.getString("userName"));
        j.put("pressingIndexThreshold", d.getDouble("pressingIndexThreshold"));
        j.put("pressingDurationThreshold", d.getInteger("pressingDurationThreshold"));
        jsonObj.append("user", j);
      }
    }
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

    Object id = document.get("_id"); // Get the ID of the just inserted document
    JSONObject jsonObj = new JSONObject();
    jsonObj.append("id", id);
    this.content = jsonObj.toString(); // It needs to be a jsonObj, otherwise the response is not
    // sent
  }


  public void saveEventCascade(HttpServletRequest httpServletRequest) {
    String eIds = httpServletRequest.getParameter("eventIds");
    String sport = httpServletRequest.getParameter("querySport");
    // to take the eventIs and make them into a List
    List<String> eventIds = Arrays.asList(eIds.split("\\s*,\\s*"));

    MongoCollection<Document> savedCol = MongoDBRestProxy.db.getCollection("savedFilters");

    Document document = new Document("Name", httpServletRequest.getParameter("queryName"))
        .append("eventIds", eventIds);
    document.append("Sport", sport);
    savedCol.insertOne(document);

    Object id = document.get("_id");
    JSONObject jsonObj = new JSONObject();
    jsonObj.append("id", id);
    this.content = jsonObj.toString();
  }


  /**
   * This function saves a new user and stores it with the corresponding values in the DB.
   */
  public void saveUser(HttpServletRequest httpServletRequest) {
    String role = String.valueOf(httpServletRequest.getParameter("userRole"));
    String userName = String.valueOf(httpServletRequest.getParameter("userName"));
    double pressingIndexThreshold =
        Double.valueOf(httpServletRequest.getParameter("pressingIndexThreshold"));
    int pressingDurationThreshold =
        Integer.valueOf(httpServletRequest.getParameter("pressingDurationThreshold"));

    MongoCollection<Document> savedUser = MongoDBRestProxy.db.getCollection("users");
    Document document = new Document();
    document.append("role", role);
    document.append("userName", userName);
    document.append("pressingIndexThreshold", pressingIndexThreshold);
    document.append("pressingDurationThreshold", pressingDurationThreshold);
    savedUser.insertOne(document);

    Object id = document.get("_id");
    JSONObject jsonObj = new JSONObject();
    jsonObj.append("id", id);
    this.content = jsonObj.toString();
  }


  /**
   * This function updates settings for a specific user in the DB.
   */
  public void customizeSettings(HttpServletRequest httpServletRequest) {
    // get values from request
    String userName = String.valueOf(httpServletRequest.getParameter("userName"));
    double pressingIndexThreshold =
        Double.valueOf(httpServletRequest.getParameter("pressingIndexThreshold"));
    int pressingDurationThreshold =
        Integer.valueOf(httpServletRequest.getParameter("pressingDurationThreshold"));

    MongoCursor<Document> cursor = MongoDBRestProxy.usersCollection.find().iterator();
    while (cursor.hasNext()) {
      Document d = cursor.next();
      // update values if the same userName

      if (d.getString("userName").equals(userName)) {
        // get old values from DB
        double oldPressValue = d.getDouble("pressingIndexThreshold");
        int oldDurationValue = d.getInteger("pressingDurationThreshold");

        // document of corresponding user, where values have to be replaced
        BasicDBObject oldval = new BasicDBObject();
        oldval.put("userName", userName);
        oldval.put("pressingIndexThreshold", oldPressValue);
        oldval.put("pressingDurationThreshold", oldDurationValue);

        // new values which should replace the old ones
        BasicDBObject newval = new BasicDBObject();
        newval.put("pressingIndexThreshold", pressingIndexThreshold);
        newval.put("pressingDurationThreshold", pressingDurationThreshold);

        // pass in the $set operator to update the specified field
        BasicDBObject update = new BasicDBObject();
        update.put("$set", newval);

        MongoDBRestProxy.usersCollection.updateOne(oldval, update);
      }
    }

    JSONObject jsonObj = new JSONObject();
    jsonObj.append("userName", userName);
    jsonObj.append("pressingIndexThreshold", pressingIndexThreshold);
    jsonObj.append("pressingDurationThreshold", pressingDurationThreshold);

    this.content = jsonObj.toString();
  }


  /**
   * Gets all the saved queries from the Database, only the name and the ID.
   */
  public void getQueries(HttpServletRequest httpServletRequest) {
    // To first get the elements that were added last, we sort them in descending order
    MongoCursor<Document> cursor =
        MongoDBRestProxy.queryCollection.find().sort(new BasicDBObject("_id", -1)).allowDiskUse(true).iterator();
    List<NameID> queries = new ArrayList<>();

    String queryFilter;
    if (httpServletRequest.getParameterMap().containsKey("sportFilter")) {
      String sportFilter = httpServletRequest.getParameter("sportFilter");
      while (cursor.hasNext()) {
        Document d = cursor.next();
        queryFilter = d.get("Sport").toString();

        if (sportFilter.equals(queryFilter)) {
          String queryID = d.get("_id").toString();
          String queryName = d.get("Name").toString();
          NameID q = new NameID(queryName, queryFilter, queryID);
          queries.add(q);
        }
      }
    } else {
      while (cursor.hasNext()) {
        Document d = cursor.next();
        String queryID = d.get("_id").toString();
        String queryName = d.get("Name").toString();

        NameID q = new NameID(queryName, queryID);
        queries.add(q);
      }
    }

    JSONObject jsonObject = null;
    JSONObject resultJsonObject = new JSONObject();

    for (int i = 0; i < queries.size(); i++) {
      jsonObject = new JSONObject();
      NameID q = queries.get(i);
      jsonObject.put("name", q.name);
      jsonObject.put("qid", q.id);
      jsonObject.put("sport", q.sport);
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
          .sort(new BasicDBObject("time", 1)).allowDiskUse(true).iterator();
    } else {
      queryCursor = MongoDBRestProxy.eventCollection.find(Filters.or(queryFilters))
          .sort(new BasicDBObject("time", 1)).allowDiskUse(true).iterator(); // This is when the IDs of the events are
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


  public void analyzeTeams(HttpServletRequest httpServletRequest) {
    String user = httpServletRequest.getParameter("user");
    String teams = httpServletRequest.getParameter("teams");
    String teamsArray[] = teams.split(",");
    String parameters = httpServletRequest.getParameter("parameters");
    String parametersArray[] = parameters.split(",");
    String matches = httpServletRequest.getParameter("matches");
    String matchesArray[] = matches.split(",");

    FindIterable<Document> findIterable;
    JSONObject teamsJson = new JSONObject();

    for (int i = 0; i < teamsArray.length; i++) {
      // Set team ID, not the name, we will get that in the client
      Team t = new Team(teamsArray[i]);

      boolean continueAnalysis = true;

      // Fill the teams with all the values
      String teamID = teamsArray[i];

      // this is used if at least one match is selected in the Team Analysis UI
      List<Bson> matchFilters = new ArrayList<>();
      if (matchesArray[0] != "") {
        for (int j = 0; j < matchesArray.length; j++) {
          matchFilters.add(Filters.eq("matchId", matchesArray[j]));
        }
      }

      /*
       * To get all the games a team has played in
       */
      List<Bson> playedMatches = new ArrayList<>();
      playedMatches
          .add(Filters.or(Filters.eq("homeTeamId", teamID), Filters.eq("awayTeamId", teamID)));

      if (matchesArray[0] != "") {
        playedMatches.add(Filters.and(Filters.or(matchFilters)));
      }

      findIterable = MongoDBRestProxy.matchCollection.find(Filters.and(playedMatches));
      for (Document doc : findIterable) {
        t.gamesPlayed++;
        // Here check if the team won that match
        int score = getMatchOutcomeTeam(doc, teamID);
        switch (score) {
          case 1:
            t.gamesWon++;
            break;
          case 2:
            t.gamesDrawn++;
            break;
          case 3:
            t.gamesLost++;
            break;
          default:
            break;
        }
      }

      // if the team has not played a single game there is no need to continue the analysis
      if (t.gamesPlayed == 0) {
        continueAnalysis = false;
      }

      // otherwise execute the team Analysis
      if (continueAnalysis) {
        /*
         * Get all successful Passes by a Team
         */
        List<Bson> succPasses = new ArrayList<>();
        succPasses.add(Filters.eq("type", "successfulPassEvent"));
        succPasses.add(Filters.eq("teamIds.0", teamID));
        if (matchesArray[0] != "") {
          succPasses.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(succPasses));

        for (Document doc : findIterable) {
          t.successfulPasses++;
          t.totalTouches++;
          Document additional = (Document) doc.get("additionalInfo");
          if (additional.get("length") != null) {
            t.totalPassLength += Double.parseDouble(String.valueOf(additional.get("length")));
          }
          if (additional.get("packing") != null) {
            t.totalPacking += Double.parseDouble(String.valueOf(additional.get("packing")));
          } else {
            t.totalPacking = 0;
          }
          if (additional.get("velocity") != null) {
            t.totalPassVelocity += Double.parseDouble(String.valueOf(additional.get("velocity")));
          } else {
            t.totalPassVelocity = 0;
          }
          if (additional.getString("direction") != null) {
            String passDirection = additional.getString("direction");
            switch (passDirection) {
              case "forward":
                t.forwardPasses++;
                break;
              case "backward":
                t.backwardPasses++;
                break;
              case "left":
                t.leftPasses++;
                break;
              case "right":
                t.rightPasses++;
                break;
            }
          }
          if (additional.get("length") != null) {
            if (Double.parseDouble(String.valueOf(additional.get("length"))) > 30) {
              t.longPasses++;
            } else {
              t.shortPasses++;
            }
          }

        }
        if (t.successfulPasses != 0) {
          t.avgPassLength = Math.floor((t.totalPassLength / t.successfulPasses) * 100) / 100;
          if (t.totalPassVelocity != 0) {
            t.avgPassVelocity = Math.floor((t.totalPassVelocity / t.successfulPasses) * 100) / 100;
          } else {
            t.avgPassVelocity = 0;
          }
          if (t.totalPacking != 0) {
            t.avgPacking = Math.floor((t.totalPacking / t.successfulPasses) * 100) / 100;
          } else {
            t.avgPacking = 0;
          }
        }

        /*
         * Get the failed Passes from the interceptions the players of a team made from the DB. The
         * first player in the interceptions Data made a pass that was intercepted by a second
         * player. Note: interceptions are misplaced passes but do not appear so in the statistics,
         * hence interceptions are here taken into account for misplaced passes statistic!
         */
        List<Bson> misplacedPasses = new ArrayList<>();
        misplacedPasses.add(Filters.eq("type", "interceptionEvent"));
        // to get the first index of the array
        misplacedPasses.add(Filters.eq("teamIds.0", teamID));
        if (matchesArray[0] != "") {
          misplacedPasses.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(misplacedPasses));
        for (Document doc : findIterable) {
          t.misplacedPasses++;
          t.totalTouches++;
        }

        /*
         * Get all failed Passes by a Team
         */
        List<Bson> failedPasses = new ArrayList<>();
        failedPasses.add(Filters.eq("type", "misplacedPassEvent"));
        failedPasses.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          failedPasses.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(failedPasses));
        for (Document doc : findIterable) {
          t.misplacedPasses++;
          t.totalTouches++;
        }

        if ((t.successfulPasses + t.misplacedPasses) != 0) {
          t.passAccuracy = Math.floor(
              ((double) t.successfulPasses / (double) (t.successfulPasses + t.misplacedPasses))
                  * 10000)
              / 100; // To get the percentage just right
        } else {
          t.passAccuracy = 0;
        }

        /*
         * Get all the Shots on Target by a Team
         */
        List<Bson> shotsOnTarget = new ArrayList<>();
        shotsOnTarget.add(Filters.eq("type", "shotOnTargetEvent"));
        shotsOnTarget.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          shotsOnTarget.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(shotsOnTarget));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.totalShots++;
          t.shotsOnTarget++;
          Document additional = (Document) doc.get("additionalInfo");
          if (additional.get("length") != null) {
            t.totalShotLength += Double.parseDouble(String.valueOf(additional.getDouble("length")));
          }
          if (additional.get("velocity") != null) {
            t.totalShotVelocity +=
                Double.parseDouble(String.valueOf(additional.getDouble("velocity")));
          }
        }

        /*
         * Get all the Shots off Target by a Team
         */
        List<Bson> shotsOffTarget = new ArrayList<>();
        shotsOffTarget.add(Filters.eq("type", "shotOffTargetEvent"));
        shotsOffTarget.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          shotsOffTarget.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(shotsOffTarget));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.totalShots++;
          t.shotsOffTarget++;
          Document additional = (Document) doc.get("additionalInfo");
          if (additional.get("length") != null) {
            t.totalShotLength += Double.parseDouble(String.valueOf(additional.getDouble("length")));
          }
          if (additional.get("velocity") != null) {
            t.totalShotVelocity +=
                Double.parseDouble(String.valueOf(additional.getDouble("velocity")));
          }
        }

        /*
         * Get all the goals by a Team
         */
        List<Bson> goals = new ArrayList<>();
        goals.add(Filters.eq("type", "goalEvent"));
        goals.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          goals.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(goals));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.totalShots++;
          t.goals++;

          // To check if the Event has the required additionalIfno, like in the Ice-hockey we can't
          // make the query because it only has BallPos as additionalInfo in the goalEvent
          Bson goalsWithAdditionalnfo = Filters.and(Filters.eq("_id", doc.get("_id")),
              Filters.exists("additionalInfo.length", true));

          findIterable = MongoDBRestProxy.eventCollection.find(goalsWithAdditionalnfo);
          for (Document d : findIterable) {
            Document additional = (Document) d.get("additionalInfo");
            if (additional.get("length") != null) {
              t.totalShotLength +=
                  Double.parseDouble(String.valueOf(additional.getDouble("length")));
            }
            if (additional.get("velocity") != null) {
              t.totalShotVelocity +=
                  Double.parseDouble(String.valueOf(additional.getDouble("velocity")));
            }
          }
        }

        if (t.totalShots != 0) {
          t.avgShotLength = Math.floor((t.totalShotLength / (double) t.totalShots) * 100) / 100;
          t.avgShotVelocity = Math.floor((t.totalShotVelocity / (double) t.totalShots) * 100) / 100;
        }

        /*
         * Get all the dribblings by a Team
         */
        List<Bson> dribblings = new ArrayList<>();
        dribblings.add(Filters.eq("type", "dribblingStatistics"));
        dribblings.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          dribblings.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.statisticsCollection.find(Filters.and(dribblings));
        for (Document doc : findIterable) {
          // t.totalTouches++;
          t.dribblings++;
          // Currently only counting number of dribblings without using additional Info, could
          // change that below.
          Document additional = (Document) doc.get("additionalInfo");
        }

        /*
         * Get the interceptions the Team made from the DB
         */
        List<Bson> interceptions = new ArrayList<>();
        interceptions.add(Filters.eq("type", "interceptionEvent"));
        // to get the second index of the array
        interceptions.add(Filters.eq("teamIds.1", teamID));
        if (matchesArray[0] != "") {
          interceptions.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(interceptions));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.interceptions++;
        }

        /*
         * Get the clearances the Team made from the DB
         */
        List<Bson> clearances = new ArrayList<>();
        clearances.add(Filters.eq("type", "clearanceEvent"));
        // to get the first index of the array
        clearances.add(Filters.eq("teamIds.0", teamID));
        if (matchesArray[0] != "") {
          clearances.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(clearances));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.clearances++;
        }

        /*
         * Get the pressing phases and the pressing phases against the Team from the DB
         */
        double pressure_sum = 0;
        double under_pressure_sum = 0;
        double global_count_underPres = 0;
        double global_count_Pres = 0;
        double pressingIndexThreshold = 0.0;
        int pressingDurationThreshold = 0;

        // if no user is selected take default values
        if (user.equals("Select User")) {
          pressingIndexThreshold =
              Double.valueOf(httpServletRequest.getParameter("pressingIndexThreshold"));
          pressingDurationThreshold =
              Integer.valueOf(httpServletRequest.getParameter("pressingDurationThreshold"));
        } else {
          // take user specific values from DB
          MongoCursor<Document> cursor = MongoDBRestProxy.usersCollection.find().iterator();
          while (cursor.hasNext()) {
            Document d = cursor.next();
            if (d.getString("userName").equals(user)) {
              pressingIndexThreshold = d.getDouble("pressingIndexThreshold");
              pressingDurationThreshold = d.getInteger("pressingDurationThreshold");
            }
          }
        }

        findIterable = MongoDBRestProxy.matchCollection.find(Filters.and(playedMatches));

        // loop through(filtered) matches collection
        for (Document doc : findIterable) {
          String matchId = doc.get("matchId").toString();
          int start_ts = 0;
          int end_ts = 0;
          int ts = 0;
          String t_id = "";
          double Pressure = 0;
          int counter = 0;
          int opp_counter = 0;
          double sum = 0;
          double opp_sum = 0;

          List<Bson> queryFilters = new ArrayList<Bson>();
          queryFilters.add(Filters.eq("type", "pressingState"));
          queryFilters.add(Filters.eq("matchId", matchId));

          // loop through states collection (sorted by ts)
          MongoCursor<Document> cursor = MongoDBRestProxy.statesCollection
              .find(Filters.and(queryFilters)).sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();

          while (cursor.hasNext()) {
            Document d = cursor.next();
            Document dp = (Document) d.get("additionalInfo");
            Pressure = dp.getDouble("pressingIndex");
            ts = d.getInteger("ts");

            // If Pressure above threshold
            if (Pressure >= pressingIndexThreshold) {
              JSONArray jt_ids = new JSONArray(d.get("teamIds").toString());
              t_id = jt_ids.getString(0);

              // if team underPressure
              if (t_id.equals(teamID)) {
                opp_sum = 0;
                opp_counter = 0;
                if (counter == 0) { // set start values
                  start_ts = ts;
                  sum = Math.round(Pressure * 100.0) / 100.0;
                  counter++;
                } else {
                  sum += Math.round(Pressure * 100.0) / 100.0;
                  counter++;
                }
              }
              // if opponent underPressure
              else {
                sum = 0;
                counter = 0;
                if (opp_counter == 0) { // set start values
                  start_ts = ts;
                  opp_sum = Math.round(Pressure * 100.0) / 100.0;
                  opp_counter++;
                } else {
                  opp_sum += Math.round(Pressure * 100.0) / 100.0;
                  opp_counter++;
                }
              }
            }

            // team underPressure Phase
            else if (counter > 10) {
              end_ts = ts;
              // If duration above threshold --> Pressure Phase detected
              if (end_ts - start_ts >= pressingDurationThreshold) {
                t.totalUnderPressurePhases++;
                under_pressure_sum += sum;
                global_count_underPres += counter;
                counter = 0;
                sum = 0;
              }
            }
            // opponent underPressure Phase
            else if (opp_counter > 10) {
              end_ts = ts;
              // If duration above threshold --> Pressure Phase detected
              if (end_ts - start_ts >= pressingDurationThreshold) {
                t.totalPressurePhases++;
                pressure_sum += opp_sum;
                global_count_Pres += opp_counter;
                opp_counter = 0;
                opp_sum = 0;
              }
            } else {
              // reset values
              counter = 0;
              sum = 0;
              opp_counter = 0;
              opp_sum = 0;
              start_ts = 0;
              end_ts = 0;
              ts = 0;
              t_id = "";
              Pressure = 0;
            }
          }
          cursor.close();
        }

        if (global_count_underPres != 0 && t.gamesPlayed != 0) {
          t.avgUnderPressurePhasesPerGame = t.totalUnderPressurePhases / t.gamesPlayed;
          t.avgUnderPressureIndex =
              Math.round((under_pressure_sum / global_count_underPres) * 100.0) / 100.0;
        }
        if (global_count_Pres != 0 && t.gamesPlayed != 0) {
          t.avgPhasesPerGame = t.totalPressurePhases / t.gamesPlayed;
          t.avgPressureIndex = Math.round((pressure_sum / global_count_Pres) * 100.0) / 100.0;
        }

        /*
         * Get the transition phases from the DB
         */
        findIterable = MongoDBRestProxy.matchCollection.find(Filters.and(playedMatches));

        for (Document doc : findIterable) {

          // filter for eventCollection
          List<Bson> queryFilters = new ArrayList<Bson>();
          queryFilters.add(Filters.eq("matchId", doc.getString("matchId")));
          queryFilters.add(Filters.eq("type", "ballPossessionChangeEvent"));
          queryFilters.add(Filters.size("teamIds", 1));

          // offensive transitions
          MongoCursor<Document> cursor = MongoDBRestProxy.eventCollection
              .find(Filters.and(queryFilters)).sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();

          while (cursor.hasNext()) {
            Document d = cursor.next();
            int ts = d.getInteger("ts");
            String team = ((ArrayList<String>) d.get("teamIds")).get(0);

            // check if that is the opponent team
            if (!team.equals(teamID) && cursor.hasNext()) {
              // check the next event
              Document d2 = cursor.next();
              String team2 = ((ArrayList<String>) d2.get("teamIds")).get(0);
              int start_ts = d2.getInteger("ts");

              // check if possession changed from the opponent team to the team to be analyzed
              if (team2.equals(teamID) && (start_ts-ts < 3000)) {
                // offensive transition detected
                t.totalTransOff++;
              }
            }
          }

          // defensive transitions
          cursor = MongoDBRestProxy.eventCollection.find(Filters.and(queryFilters))
              .sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();

          while (cursor.hasNext()) {
            Document d = cursor.next();
            int ts = d.getInteger("ts");
            String team = ((ArrayList<String>) d.get("teamIds")).get(0);

            // check if that is the team to be analyzed
            if (team.equals(teamID) && cursor.hasNext()) {
              // check the next event
              Document d2 = cursor.next();
              String team2 = ((ArrayList<String>) d2.get("teamIds")).get(0);
              int start_ts = d2.getInteger("ts");

              // check if possession changed from the team to be analyzed to the opponent team
              if (!team2.equals(teamID) && (start_ts-ts < 3000)) {
                // defensive transition detected
                t.totalTransDef++;
              }
            }
          }
        }

        if (t.totalTransOff != 0 && t.gamesPlayed != 0) {
          t.avgTransOff = Math.floor((t.totalTransOff / t.gamesPlayed) * 100) / 100;
        }

        if (t.totalTransDef != 0 && t.gamesPlayed != 0) {
          t.avgTransDef = Math.floor((t.totalTransDef / t.gamesPlayed) * 100) / 100;
        }

        /*
         * Get the cornerkicks from the DB
         */
        List<Bson> corners = new ArrayList<>();
        corners.add(Filters.eq("type", "cornerkickEvent"));
        corners.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          corners.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(corners));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.cornerkicks++;
        }

        /*
         * Get the freekicks from the DB
         */
        List<Bson> freekicks = new ArrayList<>();
        freekicks.add(Filters.eq("type", "freekickEvent"));
        freekicks.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          freekicks.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(freekicks));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.freekicks++;
        }

        /*
         * Get the throwins from the DB
         */
        List<Bson> throwin = new ArrayList<>();
        throwin.add(Filters.eq("type", "throwinEvent"));
        throwin.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          throwin.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(throwin));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.throwins++;
        }

        /*
         * Get the dumpEvents from the DB
         */
        List<Bson> dump = new ArrayList<>();
        dump.add(Filters.eq("type", "dumpEvent"));
        dump.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          throwin.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(dump));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.dumps++;
        }

        /*
         * Get the entryEvents from the DB
         */
        List<Bson> entry = new ArrayList<>();
        entry.add(Filters.eq("type", "entryEvent"));
        entry.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          entry.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(entry));
        for (Document doc : findIterable) {
          t.entries++;
        }

        /*
         * Get the shiftEvents from the DB
         */
        List<Bson> shift = new ArrayList<>();
        shift.add(Filters.eq("type", "shiftEvent"));
        shift.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          shift.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(shift));
        for (Document doc : findIterable) {
          t.shifts++;
        }

        /*
         * Get the faceOffEvents from the DB
         */
        List<Bson> faceOff = new ArrayList<>();
        faceOff.add(Filters.eq("type", "faceOffEvent"));
        faceOff.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          faceOff.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(faceOff));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.faceOffs++;
        }

        /*
         * Get the shotOnTargetEvent from the DB
         */
        List<Bson> shotAtGoal = new ArrayList<>();
        shotAtGoal.add(Filters.eq("type", "shotAtGoalEvent"));
        shotAtGoal.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          shotAtGoal.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(shotAtGoal));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.totalShots++;
          t.shotsOnTarget++;
          Document additional = (Document) doc.get("additionalInfo");
          if (additional.get("length") != null) {
            t.totalShotLength += Double.parseDouble(String.valueOf(additional.getDouble("length")));
          }
          if (additional.get("velocity") != null) {
            t.totalShotVelocity +=
                    Double.parseDouble(String.valueOf(additional.getDouble("velocity")));
          }
        }

        /*
         * Get the penaltyEvents from the DB
         */
        List<Bson> penalty = new ArrayList<>();
        penalty.add(Filters.eq("type", "penaltyEvent"));
        penalty.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          penalty.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(penalty));
        for (Document doc : findIterable) {
          t.penalties++;
        }

        /*
         * Get the stickhandlingEvents from the DB
         */
        List<Bson> stickhandling = new ArrayList<>();
        stickhandling.add(Filters.eq("type", "stickhandlingEvent"));
        stickhandling.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          stickhandling.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(stickhandling));
        for (Document doc : findIterable) {
          t.stickhandlings++;
        }

        /*
         * Get the takeOns from the DB (OPTA data)
         */
        List<Bson> takeon = new ArrayList<>();
        takeon.add(Filters.eq("type", "successfulTakeOnEvent"));
        takeon.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          takeon.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(takeon));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.takeons++;
        }

        /*
         * Get the failed takeOns from the DB (OPTA data)
         */
        List<Bson> failedTakeon = new ArrayList<>();
        failedTakeon.add(Filters.eq("type", "failedTakeOnEvent"));
        failedTakeon.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          failedTakeon.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(failedTakeon));
        for (Document doc : findIterable) {
          t.totalTouches++;
          t.failedTakeons++;
        }

        /*
         * Get the fouls committed by players of the team from the DB (OPTA data)
         */
        List<Bson> fouls = new ArrayList<>();
        fouls.add(Filters.eq("type", "playerFoulsEvent"));
        fouls.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          fouls.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(fouls));
        for (Document doc : findIterable) {
          t.foulsCommitted++;
        }

        /*
         * Get the fouls against players of the team from the DB (OPTA data)
         */
        List<Bson> fouls_against = new ArrayList<>();
        fouls_against.add(Filters.eq("type", "playerGetFouledEvent"));
        fouls_against.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          fouls_against.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(fouls_against));
        for (Document doc : findIterable) {
          t.foulsAgainst++;
        }

        /*
         * Get the substitutions of a team from the DB (OPTA data)
         */
        List<Bson> sub_on = new ArrayList<>();
        sub_on.add(Filters.eq("type", "playerOnEvent"));
        sub_on.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          sub_on.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(sub_on));
        for (Document doc : findIterable) {
          t.playerOn++;
        }

        /*
         * Get the substitutions (OFF) of a player from the DB (OPTA data)
         */
        List<Bson> sub_off = new ArrayList<>();
        sub_off.add(Filters.eq("type", "playerOffEvent"));
        sub_off.add(Filters.eq("teamIds", teamID));
        if (matchesArray[0] != "") {
          sub_off.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(sub_off));
        for (Document doc : findIterable) {
          t.playerOff++;
        }
        if (t.gamesPlayed != 0) {
          t.subsPerGame = Math.floor((t.playerOn / t.gamesPlayed) * 100) / 100;
        } else {
          t.subsPerGame = 0;
        }
      }

      JSONArray teamStats = new JSONArray();

      for (int j = 0; j < parametersArray.length; j++) {
        switch (parametersArray[j]) {
          case "gamesPlayed":
            teamStats.put(t.gamesPlayed);
            break;
          case "gamesWon":
            teamStats.put(t.gamesWon);
            break;
          case "gamesLost":
            teamStats.put(t.gamesLost);
            break;
          case "gamesDrawn":
            teamStats.put(t.gamesDrawn);
            break;
          case "winPercentage":
            if (t.gamesPlayed != 0) { // To avoid error if the team hasn't played any
              t.winPercentage = ((double) t.gamesWon / ((double) t.gamesPlayed)) * 100;
            }
            teamStats.put(t.winPercentage);
            break;
          case "successfulPassEvent":
            teamStats.put(t.successfulPasses);
            break;
          case "misplacedPassEvent":
            teamStats.put(t.misplacedPasses);
            break;
          case "passAccuracy":
            teamStats.put(t.passAccuracy);
            break;
          case "shortPasses":
            teamStats.put(t.shortPasses);
            break;
          case "longPasses":
            teamStats.put(t.longPasses);
            break;
          case "leftPasses":
            teamStats.put(t.leftPasses);
            break;
          case "rightPasses":
            teamStats.put(t.rightPasses);
            break;
          case "forwardPasses":
            teamStats.put(t.forwardPasses);
            break;
          case "backwardPasses":
            teamStats.put(t.backwardPasses);
            break;
          case "avgPassLength":
            teamStats.put(t.avgPassLength);
            break;
          case "avgPassVelocity":
            teamStats.put(t.avgPassVelocity);
            break;
          case "avgPacking":
            teamStats.put(t.avgPacking);
            break;
          case "goalEvent":
            teamStats.put(t.goals);
            break;
          case "totalShots":
            teamStats.put(t.totalShots);
            break;
          case "shotOnTargetEvent":
            teamStats.put(t.shotsOnTarget);
            break;
          case "shotOffTargetEvent":
            teamStats.put(t.shotsOffTarget);
            break;
          case "avgShotVelocity":
            teamStats.put(t.avgShotVelocity);
            break;
          case "avgShotLength":
            teamStats.put(t.avgShotLength);
            break;
          case "DribblingStatistic":
            teamStats.put(t.dribblings);
            break;
          case "interceptionEvent":
            teamStats.put(t.interceptions); // Put info in misplaced passes
            break;
          case "clearanceEvent":
            teamStats.put(t.clearances);
            break;
          case "totalUnderPressurePhases":
            teamStats.put(t.totalUnderPressurePhases);
            break;
          case "avgUnderPressurePhasesPerGame":
            teamStats.put(t.avgUnderPressurePhasesPerGame);
            break;
          case "avgUnderPressureIndex":
            teamStats.put(t.avgUnderPressureIndex);
            break;
          case "totalPressurePhases":
            teamStats.put(t.totalPressurePhases);
            break;
          case "avgPhasesPerGame":
            teamStats.put(t.avgPhasesPerGame);
            break;
          case "avgPressureIndex":
            teamStats.put(t.avgPressureIndex);
            break;
          case "transitionsOffensive":
            teamStats.put(t.avgTransOff);
            break;
          case "transitionsDefensive":
            teamStats.put(t.avgTransDef);
            break;
          case "totalTouches": // get count all events + dribblingStatistics
            teamStats.put(t.totalTouches);
            break;
          case "cornerkickEvent":
            teamStats.put(t.cornerkicks);
            break;
          case "throwinEvent":
            teamStats.put(t.throwins);
            break;
          case "freekickEvent":
            teamStats.put(t.freekicks);
            break;
          case "dumpEvent":
            teamStats.put(t.dumps);
            break;
          case "entryEvent":
            teamStats.put(t.entries);
            break;
          case "shiftEvent":
            teamStats.put(t.shifts);
            break;
          case "faceOffEvent":
            teamStats.put(t.faceOffs);
            break;
          case "penaltyEvent":
            teamStats.put(t.penalties);
            break;
          case "stickhandlingEvent":
            teamStats.put(t.stickhandlings);
            break;
          case "successfulTakeOnEvent":
            teamStats.put(t.takeons);
            break;
          case "failedTakeOnEvent":
            teamStats.put(t.failedTakeons);
            break;
          case "teamFoulsEvent":
            teamStats.put(t.foulsCommitted);
            break;
          case "teamGetFouledEvent":
            teamStats.put(t.foulsAgainst);
            break;
          case "subsPerGame":
            teamStats.put(t.subsPerGame);
            break;
          default:
            break;
        }
      }
      teamsJson.append(teamsArray[i], teamStats);
    }
    this.content = teamsJson.toString();
  }


  // Returns the match outcome for the Team Analysis
  public int getMatchOutcomeTeam(Document d, String tID) {
    int outcome;
    int TeamScore;
    int otherTeamScore;
    List<String> homeTeamList = new ArrayList<String>();
    List<String> awayTeamList = new ArrayList<String>();

    /*
     * To get all the games a team has played in
     */
    Bson matches = Filters.or(Filters.eq("homeTeamId", tID), Filters.eq("awayTeamId", tID));
    FindIterable<Document> findIterable = MongoDBRestProxy.matchCollection.find(matches);

    // loop through match-collection and fill teamlists
    for (Document doc : findIterable) {
      homeTeamList.add(doc.getString("homeTeamId"));
      awayTeamList.add(doc.getString("awayTeamId"));
    }

    String matchID = d.getString("matchId");
    String TeamID;
    String otherTeamID;

    if (homeTeamList.contains(tID)) {
      TeamID = d.getString("homeTeamId");
      otherTeamID = d.getString("awayTeamId");
    } else {
      TeamID = d.getString("awayTeamId");
      otherTeamID = d.getString("homeTeamId");
    }

    TeamScore = getGameScore(matchID, TeamID);
    otherTeamScore = getGameScore(matchID, otherTeamID);

    if (TeamScore > otherTeamScore) { // Team wins
      outcome = 1;
    } else if (TeamScore == otherTeamScore) { // Team draws
      outcome = 2;
    } else { // Team loses
      outcome = 3;
    }
    return outcome;
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


  public void analyzePlayers(HttpServletRequest httpServletRequest) {
    String players = httpServletRequest.getParameter("players");
    String playersArray[] = players.split(",");
    String parameters = httpServletRequest.getParameter("parameters");
    String parametersArray[] = parameters.split(",");
    String matches = httpServletRequest.getParameter("matches");
    String matchesArray[] = matches.split(",");

    FindIterable<Document> findIterable;
    JSONObject playersJson = new JSONObject();

    for (int i = 0; i < playersArray.length; i++) {
      // Set player ID, not the name, we will get that in the client
      Player p = new Player(playersArray[i]);

      boolean continueAnalysis = true;

      // Fill the players with all the values
      String playerID = playersArray[i];

      // this is used if at least one match is selected in the Team Analysis UI
      List<Bson> matchFilters = new ArrayList<>();
      if (matchesArray[0] != "") {
        for (int j = 0; j < matchesArray.length; j++) {
          matchFilters.add(Filters.eq("matchId", matchesArray[j]));
        }
      }

      /*
       * To get all the games a player has played in
       */
      List<Bson> playedMatches = new ArrayList<>();
      playedMatches.add(
          Filters.or(Filters.eq("homePlayerIds", playerID), Filters.eq("awayPlayerIds", playerID)));

      if (matchesArray[0] != "") {
        playedMatches.add(Filters.and(Filters.or(matchFilters)));
      }

      findIterable = MongoDBRestProxy.matchCollection.find(Filters.and(playedMatches));
      for (Document doc : findIterable) {
        p.gamesPlayed++;
        // Here check if the player won that match getMatchOutcome(doc, playerID)
        int score = getMatchOutcomePlayer(doc, playerID);
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

      // if the player has not played a single game there is no need to continue the analysis
      if (p.gamesPlayed == 0) {
        continueAnalysis = false;
      }

      // otherwise execute the player Analysis
      if (continueAnalysis) {

        /*
         * Get all successful Passes by a Player
         */
        List<Bson> succPasses = new ArrayList<>();
        succPasses.add(Filters.eq("type", "successfulPassEvent"));
        succPasses.add(Filters.eq("playerIds.0", playerID));
        if (matchesArray[0] != "") {
          succPasses.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(succPasses));

        for (Document doc : findIterable) {
          p.successfulPasses++;
          p.totalTouches++;
          Document additional = (Document) doc.get("additionalInfo");
          if (additional.get("length") != null) {
            p.totalPassLength += Double.parseDouble(String.valueOf(additional.get("length")));
            if (Double.parseDouble(String.valueOf(additional.get("length"))) > 30) {
              p.longPasses++;
            } else {
              p.shortPasses++;
            }
          } else {
            // this does not influence the average pass length calculation:
            // no length-info means totalpasslength=0 AND avgpasslength=0
            p.totalPassLength = 0;
          }
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
         * Get the failed Passes from the interceptions the player made from the DB. The first
         * player in the interceptions Data made a pass that was intercepted by a second player.
         * Note: interceptions are misplaced passes but do not appear so in the statistics, hence
         * interceptions are here taken into account for misplaced passes statistic!
         */
        List<Bson> misplacedPasses = new ArrayList<>();
        misplacedPasses.add(Filters.eq("type", "interceptionEvent"));
        // to get the first index of the array
        misplacedPasses.add(Filters.eq("playerIds.0", playerID));
        if (matchesArray[0] != "") {
          misplacedPasses.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(misplacedPasses));
        for (Document doc : findIterable) {
          p.misplacedPasses++;
          p.totalTouches++;
        }

        /*
         * Get all failed Passes by a Player
         */
        List<Bson> failedPasses = new ArrayList<>();
        failedPasses.add(Filters.eq("type", "misplacedPassEvent"));
        failedPasses.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          failedPasses.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(failedPasses));
        for (Document doc : findIterable) {
          p.misplacedPasses++;
          p.totalTouches++;
        }

        if ((p.successfulPasses + p.misplacedPasses) != 0) {
          p.passAccuracy = Math.floor(
              ((double) p.successfulPasses / (double) (p.successfulPasses + p.misplacedPasses))
                  * 10000)
              / 100; // To get the percentage just right
        } else {
          p.passAccuracy = 0;
        }

        /*
         * Get all the Shots on Target by a Player
         */
        List<Bson> shotsOnTarget = new ArrayList<>();
        shotsOnTarget.add(Filters.eq("type", "shotOnTargetEvent"));
        shotsOnTarget.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          shotsOnTarget.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(shotsOnTarget));
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
        List<Bson> shotsOffTarget = new ArrayList<>();
        shotsOffTarget.add(Filters.eq("type", "shotOffTargetEvent"));
        shotsOffTarget.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          shotsOffTarget.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(shotsOffTarget));
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
        List<Bson> goals = new ArrayList<>();
        goals.add(Filters.eq("type", "goalEvent"));
        goals.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          goals.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(goals));
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
              p.totalShotLength +=
                  Double.parseDouble(String.valueOf(additional.getDouble("length")));
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
        List<Bson> dribblings = new ArrayList<>();
        dribblings.add(Filters.eq("type", "dribblingStatistics"));
        dribblings.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          dribblings.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.statisticsCollection.find(Filters.and(dribblings));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.dribblings++;
          // Currently only counting number of dribblings without using additional Info, could
          // change that below.
          Document additional = (Document) doc.get("additionalInfo");
        }

        /*
         * Get the interceptions the player made from the DB
         */
        List<Bson> interceptions = new ArrayList<>();
        interceptions.add(Filters.eq("type", "interceptionEvent"));
        // to get the second index of the array
        interceptions.add(Filters.eq("playerIds.1", playerID));
        if (matchesArray[0] != "") {
          interceptions.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(interceptions));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.interceptions++;
        }

        /*
         * Get the clearances the player made from the DB
         */
        List<Bson> clearances = new ArrayList<>();
        clearances.add(Filters.eq("type", "clearanceEvent"));
        // to get the first index of the array
        clearances.add(Filters.eq("playerIds.0", playerID));
        if (matchesArray[0] != "") {
          clearances.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(clearances));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.clearances++;
        }

        /*
         * Get the speedzones from the DB
         */
        // NOTE: Here we do not consider OPTA players w.r.t. the performance of the Player Analysis
        char id = playerID.charAt(0);
        char check = 'p';
        int dif = id - check;
        if (dif != 0) {
          List<Bson> speedzones = new ArrayList<>();
          speedzones.add(Filters.eq("type", "speedLevelStatistics"));
          speedzones.add(Filters.eq("playerIds.0", playerID));
          if (matchesArray[0] != "") {
            speedzones.add(Filters.and(Filters.or(matchFilters)));
          }

          // take only the last entry of the statistics
          findIterable = MongoDBRestProxy.statisticsCollection.find(Filters.and(speedzones))
              .sort(new BasicDBObject("ts", -1)).allowDiskUse(true).limit(1);

          // this code snippet would badly influence the performance with OPTA player
          for (Document doc : findIterable) {
            Document additional = (Document) doc.get("additionalInfo");
            List<Long> timeInS = (List<Long>) additional.get("timeInS");
            p.timeSpeedZone1 = timeInS.get(0);
            p.timeSpeedZone2 = timeInS.get(1);
            p.timeSpeedZone3 = timeInS.get(2);
            p.timeSpeedZone4 = timeInS.get(3);
            p.timeSpeedZone5 = timeInS.get(4);
            p.timeSpeedZone6 = timeInS.get(5);
          }
        }

        /*
         * Get the freekicks from the DB
         */
        List<Bson> freekicks = new ArrayList<>();
        freekicks.add(Filters.eq("type", "freekickEvent"));
        freekicks.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          freekicks.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(freekicks));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.freekicks++;
        }

        /*
         * Get the cornerkicks from the DB
         */
        List<Bson> corners = new ArrayList<>();
        corners.add(Filters.eq("type", "cornerkickEvent"));
        corners.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          corners.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(corners));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.cornerkicks++;
        }

        /*
         * Get the throwins from the DB
         */
        List<Bson> throwin = new ArrayList<>();
        throwin.add(Filters.eq("type", "throwinEvent"));
        throwin.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          throwin.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(throwin));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.throwins++;
        }

        /*
         * Get the dumpEvents from the DB
         */
        List<Bson> dump = new ArrayList<>();
        dump.add(Filters.eq("type", "dumpEvent"));
        dump.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          throwin.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(dump));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.dumps++;
        }

        /*
         * Get the entryEvents from the DB
         */
        List<Bson> entry = new ArrayList<>();
        entry.add(Filters.eq("type", "entryEvent"));
        entry.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          entry.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(entry));
        for (Document doc : findIterable) {
          p.entries++;
        }

        /*
         * Get the shiftEvents from the DB
         */
        List<Bson> shift = new ArrayList<>();
        shift.add(Filters.eq("type", "shiftEvent"));
        shift.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          shift.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(shift));
        for (Document doc : findIterable) {
          p.shifts++;
        }

        /*
         * Get the faceOffEvents from the DB
         */
        List<Bson> faceOff = new ArrayList<>();
        faceOff.add(Filters.eq("type", "faceOffEvent"));
        faceOff.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          faceOff.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(faceOff));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.faceOffs++;
        }

        /*
         * Get the shotOnTargetEvent from the DB
         */
        List<Bson> shotAtGoal = new ArrayList<>();
        shotAtGoal.add(Filters.eq("type", "shotAtGoalEvent"));
        shotAtGoal.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          shotAtGoal.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(shotAtGoal));
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
         * Get the penaltyEvents from the DB
         */
        List<Bson> penalty = new ArrayList<>();
        penalty.add(Filters.eq("type", "penaltyEvent"));
        penalty.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          penalty.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(penalty));
        for (Document doc : findIterable) {
          p.penalties++;
        }

        /*
         * Get the stickhandlingEvents from the DB
         */
        List<Bson> stickhandling = new ArrayList<>();
        stickhandling.add(Filters.eq("type", "stickhandlingEvent"));
        stickhandling.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          stickhandling.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(stickhandling));
        for (Document doc : findIterable) {
          p.stickhandlings++;
        }

        /*
         * Get the takeOns from the DB (OPTA data)
         */
        List<Bson> takeon = new ArrayList<>();
        takeon.add(Filters.eq("type", "successfulTakeOnEvent"));
        takeon.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          takeon.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(takeon));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.takeons++;
        }

        /*
         * Get the failed takeOns from the DB (OPTA data)
         */
        List<Bson> failedTakeon = new ArrayList<>();
        failedTakeon.add(Filters.eq("type", "failedTakeOnEvent"));
        failedTakeon.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          failedTakeon.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(failedTakeon));
        for (Document doc : findIterable) {
          p.totalTouches++;
          p.failedTakeons++;
        }

        /*
         * Get the fouls committed by a player from the DB (OPTA data)
         */
        List<Bson> fouls = new ArrayList<>();
        fouls.add(Filters.eq("type", "playerFoulsEvent"));
        fouls.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          fouls.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(fouls));
        for (Document doc : findIterable) {
          p.foulsCommitted++;
        }

        /*
         * Get the fouls against a player from the DB (OPTA data)
         */
        List<Bson> fouls_against = new ArrayList<>();
        fouls_against.add(Filters.eq("type", "playerGetFouledEvent"));
        fouls_against.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          fouls_against.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(fouls_against));
        for (Document doc : findIterable) {
          p.foulsAgainst++;
        }

        /*
         * Get the substitutions (ON) of a player from the DB (OPTA data)
         */
        List<Bson> sub_on = new ArrayList<>();
        sub_on.add(Filters.eq("type", "playerOnEvent"));
        sub_on.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          sub_on.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(sub_on));
        for (Document doc : findIterable) {
          p.playerOn++;
        }

        /*
         * Get the substitutions (OFF) of a player from the DB (OPTA data)
         */
        List<Bson> sub_off = new ArrayList<>();
        sub_off.add(Filters.eq("type", "playerOffEvent"));
        sub_off.add(Filters.eq("playerIds", playerID));
        if (matchesArray[0] != "") {
          sub_off.add(Filters.and(Filters.or(matchFilters)));
        }

        findIterable = MongoDBRestProxy.eventCollection.find(Filters.and(sub_off));
        for (Document doc : findIterable) {
          p.playerOff++;
        }
      }

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
              p.winPercentage = ((double) p.gamesWon / ((double) p.gamesPlayed)) * 100;
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
          case "timeSpeedZone1":
            playerStats.put(p.timeSpeedZone1);
            break;
          case "timeSpeedZone2":
            playerStats.put(p.timeSpeedZone2);
            break;
          case "timeSpeedZone3":
            playerStats.put(p.timeSpeedZone3);
            break;
          case "timeSpeedZone4":
            playerStats.put(p.timeSpeedZone4);
            break;
          case "timeSpeedZone5":
            playerStats.put(p.timeSpeedZone5);
            break;
          case "timeSpeedZone6":
            playerStats.put(p.timeSpeedZone6);
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
          case "dumpEvent":
            playerStats.put(p.dumps);
            break;
          case "entryEvent":
            playerStats.put(p.entries);
            break;
          case "shiftEvent":
            playerStats.put(p.shifts);
            break;
          case "faceOffEvent":
            playerStats.put(p.faceOffs);
            break;
          case "penaltyEvent":
            playerStats.put(p.penalties);
            break;
          case "stickhandlingEvent":
            playerStats.put(p.stickhandlings);
            break;
          case "freekickEvent":
            playerStats.put(p.freekicks);
            break;
          case "successfulTakeOnEvent":
            playerStats.put(p.takeons);
            break;
          case "failedTakeOnEvent":
            playerStats.put(p.failedTakeons);
            break;
          case "playerFoulsEvent":
            playerStats.put(p.foulsCommitted);
            break;
          case "playerGetFouledEvent":
            playerStats.put(p.foulsAgainst);
            break;
          case "playerOn":
            playerStats.put(p.playerOn);
            break;
          case "playerOff":
            playerStats.put(p.playerOff);
            break;
          default:
            break;
        }
      }
      playersJson.append(playersArray[i], playerStats);
    }
    this.content = playersJson.toString();
  }


  // Returns the match outcome for the Player Analysis
  public int getMatchOutcomePlayer(Document d, String pID) {
    int outcome;
    int playerTeamScore;
    int otherTeamScore;
    List<Document> homePlayerList = (List<Document>) d.get("homePlayerIds");
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

    // logger.info("timeline filter is {}", timeFilter);

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
      if (isEventQuery == true) {
        queryCursor = MongoDBRestProxy.eventCollection.find(Filters.and(queryFilters))
            .sort(new BasicDBObject("time", 1)).allowDiskUse(true).iterator();
      } else {
        queryCursor = MongoDBRestProxy.eventCollection.find(Filters.or(queryFilters))
            .sort(new BasicDBObject("time", 1)).allowDiskUse(true).iterator();
      }

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
                if (players.get(0).equals(pID) && !players.get(0).equals('0')) {
                  // TODO: check if this works, this is done for the OPTA data since the first id
                  //  is 0 then it should not count.
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
          case "dumpEvent":
            q.dumpEvents++;
            break;
          case "entryEvent":
            q.entryEvents++;
            break;
          case "shiftEvent":
            q.shiftEvents++;
            break;
          case "faceOffEvent":
            q.faceOffEvents++;
            break;
          case "shotAtGoalEvent":
            q.shotAtGoalEvents++;
          default:
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

    // 5. Loop through all the instances and count what Event is most common
    // Sort the event Types based on the average number per instance. (Add all passevents up /
    // number of instances)
    // 6. Add the events to the jsonobject if they are not 0. Start with the most popular

    JSONObject jsonObject = new JSONObject();
    jsonObject.append("queries", queries);

    this.content = json;
  }


  public void addMisplacedPassEventsData(Query q, Document additional) {
    q.misplacedPassEvents++;
    double length;
    if (additional.get("length") != null)
      length = Double.parseDouble(String.valueOf(additional.get("length")));
    else
      length = 0.0;
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
    if (additional.get("length") != null)
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
    double length;
    if (additional.get("length") != null)
      length = Double.parseDouble(String.valueOf(additional.get("length")));
    else
      length = 0.0;
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
    if (additional.get("length") != null) {
      if (q.maxPassLength < Double.parseDouble(String.valueOf(additional.get("length")))) {
        q.maxPassLength = Double.parseDouble(String.valueOf(additional.get("length")));
      }
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


  /**
   * This function returns all pressure phases from a specific match from the DB.
   */
  public void analyzePressing(HttpServletRequest request) {
    // initialize variables
    int start_ts = 0;
    int end_ts = 0;
    int duration = 0;
    int ts = 0;
    int video_ts = 0;
    String homeTeam = "";
    String awayTeam = "";
    String t_id = "";
    double pressure = 0;
    double avgPres = 0;
    double maxPres = 0;
    double minPres = 0;
    int counter = 0;
    int opp_counter = 0;
    int phase_counter = 0;
    double sum = 0;
    double opp_sum = 0;
    double pressingIndexThreshold = 0;
    int pressingDurationThreshold = 0;
    String user = "";
    JSONObject jsonObj = new JSONObject();

    user = String.valueOf(request.getParameter("userName"));

    // no user selected
    if (user.equals("Select User")) {
      // take default values
      pressingIndexThreshold = Double.valueOf(request.getParameter("pressingIndexThreshold"));
      pressingDurationThreshold =
          Integer.valueOf(request.getParameter("pressingDurationThreshold"));
    }
    // user selected
    else {
      MongoCursor<Document> cursor = MongoDBRestProxy.usersCollection.find().iterator();
      while (cursor.hasNext()) {
        Document d = cursor.next();
        // take values from DB for corresponding user
        if (d.getString("userName").equals(user)) {
          pressingIndexThreshold = d.getDouble("pressingIndexThreshold");
          pressingDurationThreshold = d.getInteger("pressingDurationThreshold");
        }
      }
    }

    JSONObject matchFilter = new JSONObject(request.getParameter("matchFilters"));
    String matchId = matchFilter.getString("match0");

    List<Bson> queryFilter = new ArrayList<Bson>();
    queryFilter.add(Filters.eq("matchId", matchId));

    FindIterable<Document> findIterable =
        MongoDBRestProxy.matchCollection.find(Filters.and(queryFilter));

    for (Document doc : findIterable) {
      homeTeam = doc.getString("homeTeamId");
      awayTeam = doc.getString("awayTeamId");
    }

    List<Bson> queryFilters = new ArrayList<Bson>();
    queryFilters.add(Filters.eq("type", "pressingState"));
    queryFilters.add(Filters.eq("matchId", matchId));

    // loop through states collection (sorted by ts)
    MongoCursor<Document> cursor = MongoDBRestProxy.statesCollection.find(Filters.and(queryFilters))
        .sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();

    while (cursor.hasNext()) {
      Document d = cursor.next();
      Document dp = (Document) d.get("additionalInfo");
      pressure = dp.getDouble("pressingIndex");
      ts = d.getInteger("ts");

      // If Pressure above threshold
      if (pressure >= pressingIndexThreshold) {
        JSONArray jt_ids = new JSONArray(d.get("teamIds").toString());
        t_id = jt_ids.getString(0);

        // if homeTeam underPressure
        if (t_id.equals(homeTeam)) {
          opp_sum = 0;
          opp_counter = 0;
          if (counter == 0) { // set start values
            start_ts = ts;
            video_ts = d.getInteger("videoTs");
            minPres = Math.round(pressure * 100.0) / 100.0;
            maxPres = Math.round(pressure * 100.0) / 100.0;
            sum = Math.round(pressure * 100.0) / 100.0;
            counter++;
          }
          // Check minimum
          else if (pressure < minPres) {
            minPres = Math.round(pressure * 100.0) / 100.0;
            sum += Math.round(pressure * 100.0) / 100.0;
            counter++;
          }
          // Check maximum
          else if (pressure > maxPres) {
            maxPres = Math.round(pressure * 100.0) / 100.0;
            sum += Math.round(pressure * 100.0) / 100.0;
            counter++;
          } else {
            sum += Math.round(pressure * 100.0) / 100.0;
            counter++;
          }
        }
        // if awayTeam underPressure
        else {
          sum = 0;
          counter = 0;
          if (opp_counter == 0) { // set start values
            start_ts = ts;
            video_ts = d.getInteger("videoTs");
            minPres = Math.round(pressure * 100.0) / 100.0;
            maxPres = Math.round(pressure * 100.0) / 100.0;
            opp_sum = Math.round(pressure * 100.0) / 100.0;
            opp_counter++;
          }
          // Check minimum
          else if (pressure < minPres) {
            minPres = Math.round(pressure * 100.0) / 100.0;
            opp_sum += Math.round(pressure * 100.0) / 100.0;
            opp_counter++;
          }
          // Check maximum
          else if (pressure > maxPres) {
            maxPres = Math.round(pressure * 100.0) / 100.0;
            opp_sum += Math.round(pressure * 100.0) / 100.0;
            opp_counter++;
          } else {
            opp_sum += Math.round(pressure * 100.0) / 100.0;
            opp_counter++;
          }
        }
      }

      // homeTeam underPressure Phase
      else if (counter > 10) {
        end_ts = ts;
        // If duration above threshold --> Pressure Phase detected
        if (end_ts - start_ts >= pressingDurationThreshold) {
          duration = end_ts - start_ts;
          phase_counter++;
          avgPres = Math.round((sum / counter) * 100.0) / 100.0;
          JSONObject j = new JSONObject();

          // search for the ball-coordinates in the states collection for visualization in the Web Client
          List<Bson> coordinateFilter = new ArrayList<Bson>();
          coordinateFilter.add(Filters.eq("type", "fieldObjectState"));
          coordinateFilter.add(Filters.eq("matchId", matchId));
          coordinateFilter.add(Filters.eq("ts", start_ts));
          coordinateFilter.add(Filters.eq("playerIds.0", "BALL"));

          double curX = 0;
          double curY = 0;

          // loop through states collection (sorted by ts)
          MongoCursor<Document> cursor2 = MongoDBRestProxy.statesCollection.find(Filters.and(coordinateFilter))
                  .sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();
          while (cursor2.hasNext()) {
            Document b = cursor2.next();
            JSONArray curXYArray = new JSONArray(b.get("xyCoords").toString());
            JSONArray curXY = new JSONArray(curXYArray.get(0).toString());
            curX = curXY.getDouble(0);
            curY = curXY.getDouble(1);
          }

          // Swap teams to get Pressure Phase instead of UnderPressure Phase
          j.put("venue", "Away");
          j.put("team", awayTeam);
          j.put("start", start_ts);
          j.put("end", end_ts);
          j.put("videoTs", video_ts);
          j.put("maxPressure", maxPres);
          j.put("minPressure", minPres);
          j.put("avgPressure", avgPres);
          j.put("duration", duration);
          j.put("startX", curX);
          j.put("startY", curY);
          j.put("matchId", matchId);
          j.put("phaseId", phase_counter);
          jsonObj.append("phase " + phase_counter, j);
          counter = 0;
          sum = 0;
        }
      }
      // awayTeam underPressure Phase
      else if (opp_counter > 10) {
        end_ts = ts;
        // If duration above threshold --> Pressure Phase detected
        if (end_ts - start_ts >= pressingDurationThreshold) {
          duration = end_ts - start_ts;
          phase_counter++;
          avgPres = Math.round((opp_sum / opp_counter) * 100.0) / 100.0;
          JSONObject j = new JSONObject();

          // search for the ball-coordinates in the states collection for visualization in the Web Client
          List<Bson> coordinateFilter = new ArrayList<Bson>();
          coordinateFilter.add(Filters.eq("type", "fieldObjectState"));
          coordinateFilter.add(Filters.eq("matchId", matchId));
          coordinateFilter.add(Filters.eq("ts", start_ts));
          coordinateFilter.add(Filters.eq("playerIds.0", "BALL"));

          double curX = 0;
          double curY = 0;

          // loop through states collection (sorted by ts)
          MongoCursor<Document> cursor2 = MongoDBRestProxy.statesCollection.find(Filters.and(coordinateFilter))
                  .sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();
          while (cursor2.hasNext()) {
            Document b = cursor2.next();
            JSONArray curXYArray = new JSONArray(b.get("xyCoords").toString());
            JSONArray curXY = new JSONArray(curXYArray.get(0).toString());
            curX = curXY.getDouble(0);
            curY = curXY.getDouble(1);
          }

          // Swap teams to get Pressure Phase instead of UnderPressure Phase
          j.put("venue", "Home");
          j.put("team", homeTeam);
          j.put("start", start_ts);
          j.put("end", end_ts);
          j.put("videoTs", video_ts);
          j.put("maxPressure", maxPres);
          j.put("minPressure", minPres);
          j.put("avgPressure", avgPres);
          j.put("duration", duration);
          j.put("startX", curX);
          j.put("startY", curY);
          j.put("matchId", matchId);
          j.put("phaseId", phase_counter);
          jsonObj.append("phase " + phase_counter, j);
          opp_counter = 0;
          opp_sum = 0;
        }
      } else {
        // reset values
        counter = 0;
        opp_counter = 0;
        sum = 0;
        opp_sum = 0;
        start_ts = 0;
        end_ts = 0;
        video_ts = 0;
        duration = 0;
        ts = 0;
        t_id = "";
        pressure = 0;
        avgPres = 0;
        maxPres = 0;
        minPres = 0;
      }
    }
    cursor.close();
    this.content = jsonObj.toString();
  }


  /**
   * This function returns the pressing index for both teams of a specific match from the DB.
   */
  public void analyzePressing2d(HttpServletRequest request) {
    // initialize variables
    int ts = 0;
    double pressing = 0.0;
    String homeTeam = "";
    String awayTeam = "";
    String otherTeamID = "";
    String venue = "";
    String otherVenue ="";
    String teamID = "";
    int counter = 0;
    JSONObject jsonObj = new JSONObject();

    JSONObject matchFilter = new JSONObject(request.getParameter("matchFilters"));
    String matchId = matchFilter.getString("match0");

    List<Bson> queryFilter = new ArrayList<Bson>();
    queryFilter.add(Filters.eq("matchId", matchId));

    FindIterable<Document> findIterable =
        MongoDBRestProxy.matchCollection.find(Filters.and(queryFilter));

    for (Document doc : findIterable) {
      homeTeam = doc.getString("homeTeamId");
      awayTeam = doc.getString("awayTeamId");
    }

    List<Bson> queryFilters = new ArrayList<Bson>();
    queryFilters.add(Filters.eq("type", "pressingState"));
    queryFilters.add(Filters.eq("matchId", matchId));

    // loop through states collection (sorted by ts)
    MongoCursor<Document> cursor = MongoDBRestProxy.statesCollection.find(Filters.and(queryFilters))
        .sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();

    int i = 0;

    while (cursor.hasNext()) {
      Document d = cursor.next();

      if (i%2 == 0) { // only every 2nd element of the collection --> performance increase
        Document dp = (Document) d.get("additionalInfo");
        pressing = Math.round(dp.getDouble("pressingIndex") * 100.0) / 100.0;
        ts = d.getInteger("ts");

        if (pressing == 0) {
          // If no pressure, EACH team gets assigned pressing 0 for this ts
          // away team
          JSONObject j = new JSONObject();
          j.put("venue", "away");
          j.put("team", "away");
          j.put("time", ts);
          j.put("pressing", pressing);
          jsonObj.append("# " + counter, j);
          counter++;

          // home team
          JSONObject k = new JSONObject();
          k.put("venue", "home");
          k.put("team", "home");
          k.put("time", ts);
          k.put("pressing", pressing);
          jsonObj.append("# " + counter, k);
          counter++;
        } else {
          JSONArray jt_ids = new JSONArray(d.get("teamIds").toString());
          teamID = jt_ids.getString(0);

          // swap teams to get pressing situation instead of underPressing situation
          if (teamID.equals(homeTeam)) {
            teamID = awayTeam;
            otherTeamID = homeTeam;
            venue = "away";
            otherVenue = "home";
          } else {
            teamID = homeTeam;
            otherTeamID = awayTeam;
            venue = "home";
            otherVenue = "away";
          }

          JSONObject j = new JSONObject();
          j.put("venue", venue);
          j.put("team", teamID);
          j.put("time", ts);
          j.put("pressing", pressing);
          jsonObj.append("# " + counter, j);
          counter++;

          // the other team needs to be assigned a pressing value of 0
          JSONObject k = new JSONObject();
          k.put("venue", otherVenue);
          k.put("team", otherTeamID);
          k.put("time", ts);
          k.put("pressing", 0);
          jsonObj.append("# " + counter, k);
          counter++;
        }
      }
      i++;
    }
    cursor.close();
    this.content = jsonObj.toString();
  }


  /**
   * This function returns all transition phases, either offensive or defensive from a specific
   * match and a specific team from the DB.
   */
  public void analyzeTransitions(HttpServletRequest request) {
    // initialize variables
    int start_ts = 0;
    int end_ts = 0;
    int duration = 0;
    int video_ts = 0;
    int phase_counter = 0;
    String homeTeam = "";
    String teamID = "";
    String matchId = "";
    String transType = "";
    String venue = "";
    JSONObject jsonObj = new JSONObject();

    // Match to be analyzed
    JSONObject matchFilter = new JSONObject(request.getParameter("matchFilters"));
    matchId = matchFilter.getString("match0");

    // Team to be analyzed
    JSONObject teamFilter = new JSONObject(request.getParameter("teamFilters"));
    teamID = teamFilter.getString("filter0");

    // Type of Transition (OFF/DEF)
    transType = request.getParameter("type");

    // needed to check if team to be analyzed is home or away team
    List<Bson> queryFilter = new ArrayList<Bson>();
    queryFilter.add(Filters.eq("matchId", matchId));

    FindIterable<Document> findIterable =
        MongoDBRestProxy.matchCollection.find(Filters.and(queryFilter));

    for (Document doc : findIterable) {
      homeTeam = doc.getString("homeTeamId");
    }

    if (teamID.equals(homeTeam)) {
      venue = "Home";
    } else {
      venue = "Away";
    }

    // filter for eventCollection
    List<Bson> queryFilters = new ArrayList<Bson>();
    queryFilters.add(Filters.eq("matchId", matchId));
    queryFilters.add(Filters.eq("type", "ballPossessionChangeEvent"));
    // a team needs to be assigned to a ballPossessionChangeEvent
    queryFilters.add(Filters.size("teamIds", 1));

    // offensive Transition
    if (transType.equals("offensive")) {
      MongoCursor<Document> cursor = MongoDBRestProxy.eventCollection
          .find(Filters.and(queryFilters)).sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();

      // loop through filtered event collection (sorted by ts)
      while (cursor.hasNext()) {
        Document d = cursor.next();
        int ts = d.getInteger("ts");
        String team = ((ArrayList<String>) d.get("teamIds")).get(0);

        // check if that is the opponent team
        if (!team.equals(teamID) && cursor.hasNext()) {
          // check the next event
          Document d2 = cursor.next();
          String team2 = ((ArrayList<String>) d2.get("teamIds")).get(0);
          start_ts = d2.getInteger("ts");

          // check if possession changed from the opponent team to the team to be analyzed and the time difference < 3000ms
          if (team2.equals(teamID) && (start_ts-ts < 3000)) {
            // offensive transition detected
            video_ts = d2.getInteger("videoTs");
            end_ts = start_ts + 5000;
            duration = end_ts - start_ts;
            phase_counter++;

            JSONArray curXYArray = new JSONArray(d2.get("xyCoords").toString());
            JSONArray curXY = new JSONArray(curXYArray.get(0).toString());
            double curX = curXY.getDouble(0);
            double curY = curXY.getDouble(1);

            JSONObject j = new JSONObject();
            j.put("team", teamID);
            j.put("venue", venue);
            j.put("start", start_ts);
            j.put("end", end_ts);
            j.put("videoTs", video_ts);
            j.put("duration", duration);
            j.put("startX", curX);
            j.put("startY", curY);
            j.put("matchId", matchId);
            j.put("phaseId", phase_counter);
            jsonObj.append("phase " + phase_counter, j);
          }
        }
      }
    }

    // defensive Transition
    else {
      MongoCursor<Document> cursor = MongoDBRestProxy.eventCollection
          .find(Filters.and(queryFilters)).sort(new BasicDBObject("ts", 1)).allowDiskUse(true).iterator();

      // loop through filtered event collection (sorted by ts)
      while (cursor.hasNext()) {
        Document d = cursor.next();
        int ts = d.getInteger("ts");
        String team = ((ArrayList<String>) d.get("teamIds")).get(0);

        // check if that is the team to be analyzed
        if (team.equals(teamID) && cursor.hasNext()) {
          // check the next event
          Document d2 = cursor.next();
          String team2 = ((ArrayList<String>) d2.get("teamIds")).get(0);
          start_ts = d2.getInteger("ts");

          // check if possession changed from the team to be analyzed to the opponent team and the time difference < 3000ms
          if (!team2.equals(teamID) && (start_ts-ts < 3000)) {
            // defensive transition detected
            video_ts = d2.getInteger("videoTs");
            end_ts = start_ts + 5000;
            duration = end_ts - start_ts;
            phase_counter++;

            JSONArray curXYArray = new JSONArray(d2.get("xyCoords").toString());
            JSONArray curXY = new JSONArray(curXYArray.get(0).toString());
            double curX = curXY.getDouble(0);
            double curY = curXY.getDouble(1);

            JSONObject j = new JSONObject();
            j.put("team", teamID);
            j.put("venue", venue);
            j.put("start", start_ts);
            j.put("end", end_ts);
            j.put("videoTs", video_ts);
            j.put("duration", duration);
            j.put("startX", curX);
            j.put("startY", curY);
            j.put("matchId", matchId);
            j.put("phaseId", phase_counter);
            jsonObj.append("phase " + phase_counter, j);
          }
        }
      }
    }
    this.content = jsonObj.toString();
  }


  /**
   * This function returns the speed of one or several players in a specific match from the DB.
   */
  public void analyzePlayerSpeed(HttpServletRequest request) {
    // initialize variables
    String homeTeam = "";
    String venue = "";
    String teamID = "";
    String playerID = "";
    int counter = 0;
    int ts = 0;
    double v = 0.0;
    boolean continueS = true;
    JSONObject jsonObj = new JSONObject();
    // manipulate the frequency (milliseconds) of entries into resultObject here
    int ts_diff = 1000;

    JSONObject matchFilter = new JSONObject(request.getParameter("matchFilters"));
    String matchId = matchFilter.getString("match0");

    List<Bson> queryFilter = new ArrayList<Bson>();
    queryFilter.add(Filters.eq("matchId", matchId));

    FindIterable<Document> findIterable =
        MongoDBRestProxy.matchCollection.find(Filters.and(queryFilter));

    for (Document doc : findIterable) {
      homeTeam = doc.getString("homeTeamId");
    }

    JSONObject playerFilters = new JSONObject(request.getParameter("playerFilters"));
    for (String key : playerFilters.keySet()) {
      playerID = playerFilters.getString(key);
      int last_ts = -1;
      // extract teamId from playerFilter and use as additional queryFilter
      String teamFilter = playerID.substring(0, 1);

      List<Bson> queryFilters = new ArrayList<Bson>();

      queryFilters.add(Filters.eq("type", "fieldObjectState"));
      queryFilters.add(Filters.eq("matchId", matchId));
      queryFilters.add(Filters.eq("teamIds.0", teamFilter));
      queryFilters.add(Filters.eq("playerIds.0", playerID));

      // loop through states collection filtered by match, team, player, and type
      // using projection of relevant fields only to increase query performance
      FindIterable<Document> findIterable2 =
          MongoDBRestProxy.statesCollection.find(Filters.and(queryFilters))
              .projection((fields(
                  include("ts", "type", "matchId", "additionalInfo", "playerIds", "teamIds"),
                  excludeId())));

      for (Document doc : findIterable2) {
        Document d = doc;
        ts = d.getInteger("ts");

        if (last_ts != -1 && (ts - last_ts) < ts_diff) { // time difference to last document
          continueS = false;
        }

        if (continueS) {
          Document dp = (Document) d.get("additionalInfo");
          // transform m/s in km/h and round
          v = Math.round((dp.getDouble("vabs") * 3.6) * 100.0) / 100.0;

          JSONArray jt_ids = new JSONArray(d.get("teamIds").toString());
          teamID = jt_ids.getString(0);

          if (teamID.equals(homeTeam)) {
            venue = "home";
          } else {
            venue = "away";
          }

          JSONObject j = new JSONObject();
          j.put("venue", venue);
          j.put("team", teamID);
          j.put("time", ts);
          j.put("speed", v);
          j.put("player", playerID);
          jsonObj.append("Player: " + playerID + " #" + counter, j);
          counter++;
          last_ts = ts;
        }
        continueS = true;
      }
      counter = 0;
      queryFilters.clear();
    }
    this.content = jsonObj.toString();
  }


  /**
   * This function returns the successful passes between all players of one team in a specific match
   * from the DB.
   */
  public void analyzePlayerPassNetwork(HttpServletRequest request) {
    // get all players of a match (adapted from the getPlayers method)
    HashMap<String, String> players = new HashMap<>();
    String homeTeam = "";
    String venue = "";

    JSONObject matchFilter = new JSONObject(request.getParameter("matchFilters"));
    String matchId = matchFilter.getString("match0");
    JSONObject teamFilter = new JSONObject(request.getParameter("teamFilters"));
    String teamID = teamFilter.getString("filter0");

    List<Bson> queryFilter = new ArrayList<Bson>();
    queryFilter.add(Filters.eq("matchId", matchId));

    FindIterable<Document> findIterable =
        MongoDBRestProxy.matchCollection.find(Filters.and(queryFilter));
    for (Document doc : findIterable) {
      homeTeam = doc.getString("homeTeamId");
    }

    MongoCursor<Document> cursor =
        MongoDBRestProxy.matchCollection.find(Filters.and(queryFilter)).iterator();

    // create a list of players and their IDs with no duplicates
    while (cursor.hasNext()) {
      Document d = cursor.next();

      if (teamID.equals(homeTeam)) {
        venue = "home";
        String h = d.get("homePlayerNames").toString();
        String hID = d.get("homePlayerIds").toString();

        String[] hPlayers = h.substring(1, h.length() - 1).split(", ");
        String[] hPlayersID = hID.substring(1, hID.length() - 1).split(", ");
        for (int i = 0; i < hPlayers.length; i++) {
          players.put(hPlayersID[i], hPlayers[i]);
        }
      } else {
        venue = "away";
        String a = d.get("awayPlayerNames").toString();
        String aID = d.get("awayPlayerIds").toString();

        String[] aPlayers = a.substring(1, a.length() - 1).split(", ");
        String[] aPlayersID = aID.substring(1, aID.length() - 1).split(", ");

        for (int i = 0; i < aPlayers.length; i++) {
          players.put(aPlayersID[i], aPlayers[i]);
        }
      }
    }

    JSONObject jsonObj = null;
    JSONObject resultJsonObj = new JSONObject();
    cursor.close();

    // now calculate the total number of (successful) passes of a player and between the players

    // first add some filter
    queryFilter.add(Filters.eq("type", "successfulPassEvent"));
    queryFilter.add(Filters.eq("teamIds.0", teamID));

    // loop through playerlist
    Iterator its = players.entrySet().iterator();

    while (its.hasNext()) {
      Map.Entry pair = (Map.Entry) its.next();
      String pid = pair.getKey().toString();
      Map<String, Integer> receivers = new HashMap<String, Integer>();
      jsonObj = new JSONObject();

      JSONObject alledges = new JSONObject();
      int totalPasses = 0;
      int totalPassesReceived = 0;

      MongoCursor<Document> eventCursor =
          MongoDBRestProxy.eventCollection.find(Filters.and(queryFilter)).iterator();

      // for each player loop through event collection
      while (eventCursor.hasNext()) {
        Document e = eventCursor.next();
        List<String> playerIds = (List<String>) e.get("playerIds");

        if (playerIds.get(0).equals(pid)) {
          totalPasses++;

          // OPTA data have no receiver
          try {
            if (playerIds.get(1) != null) {
              String receiverID = playerIds.get(1);

              if (receivers.get(receiverID) != null) {
                receivers.put(receiverID, receivers.get(receiverID) + 1);
              } else {
                receivers.put(receiverID, 1);
              }
            }
          } catch (Exception ex) {
            logger.error("OPTA Data have no receiver in successful Pass Data", ex);
          }
        }
        try {
          if (playerIds.get(1).equals(pid)) {
            totalPassesReceived++;
          }
        } catch (Exception ex) {
          logger.error("OPTA Data have no receiver in successful Pass Data", ex);
        }
      }
      eventCursor.close();

      jsonObj.put("pid", pair.getKey());
      jsonObj.put("name", pair.getValue());
      jsonObj.put("totalpasses", totalPasses);
      jsonObj.put("totalpassesreceived", totalPassesReceived);
      jsonObj.put("venue", venue);

      // jsonObj.put("avgXcoordinate", getAveragePlayerCoordinates(pid, matchId));
      // jsonObj.put("receiver", receivers);

      // loop through receiver list
      Iterator recv = receivers.entrySet().iterator();

      while (recv.hasNext()) {
        JSONObject edges = new JSONObject();
        Map.Entry recv_pair = (Map.Entry) recv.next();
        edges.put("from", pid);
        edges.put("to", recv_pair.getKey());
        edges.put("value", recv_pair.getValue());
        edges.put("venue", venue);
        alledges.append("Edges " + pid, edges);
      }

      resultJsonObj.append("Edges", alledges);
      resultJsonObj.append("Players", jsonObj);
    }

    this.content = resultJsonObj.toString();
  }


  /**
   * This function returns the average x,y-coordinates of a player of a specific match from the DB.
   */
  public double[] getAveragePlayerCoordinates(String player, String match) {
    String matchId = match;
    String playerId = player;

    double xValue = 0.0;
    double yValue = 0.0;
    int counter = 0;

    // apply filters
    List<Bson> queryFilters = new ArrayList<Bson>();
    queryFilters.add(Filters.eq("type", "fieldObjectState"));
    queryFilters.add(Filters.eq("matchId", matchId));
    queryFilters.add(Filters.eq("playerIds.0", playerId));

    // loop through states collection
    MongoCursor<Document> cursor = MongoDBRestProxy.statesCollection.find(Filters.and(queryFilters))
        .projection((fields(include("xyCoords"), excludeId()))).iterator();

    while (cursor.hasNext()) {
      Document d = cursor.next();
      counter++;

      JSONArray curXYArray = new JSONArray(d.get("xyCoords").toString());
      JSONArray curXY = new JSONArray(curXYArray.get(0).toString());
      double curX = curXY.getDouble(0);
      double curY = curXY.getDouble(1);
      xValue += curX;
      yValue += curY;
    }

    double finalX = Math.round((xValue / counter) * 100) / 100;
    double finalY = Math.round((yValue / counter) * 100) / 100;
    double[] coords = {finalX, finalY};

    return coords;
  }


  /**
   * This function returns the team colors for a match from the MongoDB.
   */
  public void getTeamSettings(HttpServletRequest request) {

    JSONObject jsonObj = new JSONObject();
    JSONObject matchFilter = new JSONObject(request.getParameter("matchFilters"));
    String matchId = matchFilter.getString("match0");

    List<Bson> queryFilters = new ArrayList<Bson>();
    queryFilters.add(Filters.eq("matchId", matchId));

    FindIterable<Document> findIterable =
        MongoDBRestProxy.matchCollection.find(Filters.and(queryFilters));

    for (Document doc : findIterable) {
      jsonObj.put("Home", doc.getString("homeTeamColor"));
      jsonObj.put("Away", doc.getString("awayTeamColor"));
      jsonObj.put("HomeID", doc.getString("homeTeamId"));
      jsonObj.put("AwayID", doc.getString("awayTeamId"));
      jsonObj.put("HomeName", doc.getString("homeTeamName"));
      jsonObj.put("AwayName", doc.getString("awayTeamName"));
    }

    this.content = jsonObj.toString();
  }


  /**
   * This function uses the input shape type and the input shape content in order to generate a
   * query to filter for geo data.
   */
  private void getGeoQuery(List<Bson> queryFilters, String shapeType, JSONObject shapeContent) {
    // adding geo queries
    if (shapeType == null) {
      // do not add any filter, when shapeType does not exist!
    } else if (shapeType.equals("null")) {
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
   * Overloads the getfilters method so it can be called with a request and with a JSON object used
   * in the "rerunQuery" method
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
   * Generic method to concatenate 2 arrays
   */
  public static <T> T[] concat(T[] first, T[] second) {
    T[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }
}


/*
 * Helper class for creating name + id lists
 */
class NameID {
  String sport;
  String name;
  String id;

  NameID(String queryName, String querySport, String id) {
    this.name = queryName;
    this.sport = querySport;
    this.id = id;
  }

  NameID(String queryName, String id) {
    this.name = queryName;
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
  int dumpEvents;
  int entryEvents;
  int shiftEvents;
  int faceOffEvents;
  int shotAtGoalEvents;

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
    this.dumpEvents = 0;
    this.entryEvents = 0;
    this.shiftEvents = 0;
    this.faceOffEvents = 0;
    this.shotAtGoalEvents = 0;
  }
}


/**
 * Helper class for the player Analysis
 */
class Player {
  String id;

  String sport;
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
  long timeSpeedZone1;
  long timeSpeedZone2;
  long timeSpeedZone3;
  long timeSpeedZone4;
  long timeSpeedZone5;
  long timeSpeedZone6;
  int clearances;
  int totalTouches;
  int freekicks;
  int cornerkicks;
  int throwins;
  int dumps;
  int entries;
  int shifts;
  int faceOffs;
  int penalties;
  int stickhandlings;

  // OPTA data
  int takeons;
  int failedTakeons;
  int foulsCommitted;
  int foulsAgainst;
  int playerOn;
  int playerOff;


  public Player(String id) {
    this.id = id;

    this.sport = "";
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
    this.timeSpeedZone1 = 0;
    this.timeSpeedZone2 = 0;
    this.timeSpeedZone3 = 0;
    this.timeSpeedZone4 = 0;
    this.timeSpeedZone5 = 0;
    this.timeSpeedZone6 = 0;
    this.clearances = 0;
    this.totalTouches = 0;
    this.freekicks = 0;
    this.cornerkicks = 0;
    this.throwins = 0;
    this.dumps = 0;
    this.entries = 0;
    this.shifts = 0;
    this.faceOffs = 0;
    this.penalties = 0;
    this.stickhandlings = 0;
    // OPTA data
    this.takeons = 0;
    this.failedTakeons = 0;
    this.foulsCommitted = 0;
    this.foulsAgainst = 0;
    this.playerOn = 0;
    this.playerOff = 0;
  }
}


/**
 * Helper class for the team Analysis
 */
class Team {

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
  int totalUnderPressurePhases;
  double avgUnderPressurePhasesPerGame;
  double avgUnderPressureIndex;
  int totalPressurePhases;
  double avgPhasesPerGame;
  double avgPressureIndex;
  int totalTransOff;
  int totalTransDef;
  double avgTransOff;
  double avgTransDef;
  int totalTouches;
  int freekicks;
  int cornerkicks;
  int throwins;
  int dumps;
  int entries;
  int shifts;
  int faceOffs;
  int penalties;
  int stickhandlings;
  // OPTA data
  int takeons;
  int failedTakeons;
  int foulsCommitted;
  int foulsAgainst;
  int playerOn;
  int playerOff;
  double subsPerGame;


  public Team(String id) {
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
    this.totalUnderPressurePhases = 0;
    this.avgUnderPressurePhasesPerGame = 0;
    this.avgUnderPressureIndex = 0;
    this.totalPressurePhases = 0;
    this.avgPhasesPerGame = 0;
    this.avgPressureIndex = 0;
    this.totalTransOff = 0;
    this.totalTransDef = 0;
    this.avgTransOff = 0;
    this.avgTransDef = 0;
    this.totalTouches = 0;
    this.freekicks = 0;
    this.cornerkicks = 0;
    this.throwins = 0;
    this.dumps = 0;
    this.entries = 0;
    this.shifts = 0;
    this.faceOffs = 0;
    this.penalties = 0;
    this.stickhandlings = 0;
    // OPTA data
    this.takeons = 0;
    this.failedTakeons = 0;
    this.foulsCommitted = 0;
    this.foulsAgainst = 0;
    this.playerOn = 0;
    this.playerOff = 0;
    this.subsPerGame = 0;
  }
}
