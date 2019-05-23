/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package socialnetwork.task.recommendation;

import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import socialnetwork.task.TaskBase;
import socialnetwork.util.Activity;
import socialnetwork.util.Config;

import java.io.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

public class FriendRecommender extends TaskBase<Activity, ArrayList<ArrayList<Integer>>> {
    
    final static Logger logger = LoggerFactory.getLogger("Task2");
    private final Integer[] eigenUserIds = Config.eigenUserIds;

    public DataStream<ArrayList<ArrayList<Integer>>> buildPipeline(StreamExecutionEnvironment env, DataStream<Activity> inputStream) {
        final ArrayList<HashSet<Integer>>
                alreadyKnows = getExistingFriendships();

        final ArrayList<HashMap<Integer, Integer>>
                staticSimilarities = getStaticSimilarities(alreadyKnows);

        /* test input stream
        DataStream<Activity> testInput =
            env.readTextFile(System.getProperty("user.home") + "/repo/eth-dspa-2019/project/data/test/task2.txt")
               .map(Activity::new)
               .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor<Activity>(Config.outOfOrdernessBound) {
                   public long extractTimestamp(Activity a) {
                       return a.timestamp;
                   }
               });
        // input.print().setParallelism(1);
        */

        // get per-post similarities with a keyed sliding window
        DataStream<ArrayList<HashMap<Integer, Integer>>> similaritiesPerPost = inputStream
            .keyBy(Activity::getPostId)
            .window(SlidingEventTimeWindows.of(Time.hours(4), Time.hours(1)))
            .aggregate(new CountActivitiesPerUser(), new GetUserSimilarities(alreadyKnows));
        // similaritiesPerPost.print().setParallelism(1);

        // Use another window to sum up the per-post similarities
        DataStream<ArrayList<ArrayList<Integer>>> recommendations = similaritiesPerPost
            .windowAll(TumblingEventTimeWindows.of(Time.hours(1)))
            .aggregate(new SimilarityAggregate(), new GetTopFiveRecommendations(staticSimilarities, Config.staticWeight));
        // recommendations.print().setParallelism(1);

        return recommendations;
    }

    public void buildTestPipeline(StreamExecutionEnvironment env) {
        final ArrayList<HashSet<Integer>>
                alreadyKnows = getExistingFriendships();

        final ArrayList<HashMap<Integer, Integer>>
                staticSimilarities = getStaticSimilarities(alreadyKnows);

        DataStream<Activity> input =
            env.readTextFile(System.getProperty("user.home") + "/repo/eth-dspa-2019/project/data/task2.txt")
               .map(Activity::fromString)
               .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor<Activity>(Config.outOfOrdernessBound) {
                   public long extractTimestamp(Activity a) {
                       return a.getCreationTimestamp();
                   }
               });
        input.print().setParallelism(1);

        // get per-post similarities with a keyed sliding window
        DataStream<ArrayList<HashMap<Integer, Integer>>> similaritiesPerPost = input
                .keyBy(Activity::getPostId)
                .window(SlidingEventTimeWindows.of(Time.hours(4), Time.hours(1)))
                .aggregate(new CountActivitiesPerUser(), new GetUserSimilarities(alreadyKnows));
        similaritiesPerPost.print().setParallelism(1);

        // Use another window to sum up the per-post similarities
        DataStream<ArrayList<ArrayList<Integer>>> recommendations = similaritiesPerPost
                .windowAll(TumblingEventTimeWindows.of(Time.hours(1)))
                .aggregate(new SimilarityAggregate(), new GetTopFiveRecommendations(staticSimilarities, Config.staticWeight));
        recommendations.print().setParallelism(1);
    }

    private ArrayList<HashSet<Integer>> getExistingFriendships() {
        // use hashmap first for easy lookup
        HashMap<Integer, HashSet<Integer>> friendSets = new HashMap<>();
        for (Integer userId : eigenUserIds) {
            friendSets.putIfAbsent(userId, new HashSet<>());
        }

        // store concerned relationships
        try {
            InputStream csvStream = new FileInputStream(Config.path_person_knows_person);
            BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream));
            // skip the header of the csv
            reader.lines().skip(1).forEach(line -> {
                String[] splits = line.split("\\|");
                Integer knower = Integer.valueOf(splits[0]);
                Integer knowee = Integer.valueOf(splits[1]);
                if (friendSets.containsKey(knower)) {
                    friendSets.get(knower).add(knowee);
                }
            });
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        // put into arraylist
        ArrayList<HashSet<Integer>> retList = new ArrayList<>();
        for (Integer userId : eigenUserIds) {
            retList.add(friendSets.get(userId));
        }
        return retList;
    }


    private void updateSimilarityWithOneCSV(ArrayList<HashMap<Integer, Integer>> similarities,
                                            ArrayList<HashSet<Integer>> alreadyKnows,
                                            String csvPath) {
        HashMap<Integer, HashSet<Integer>> setsPerUser = new HashMap<>();

        // read into a hashmap: userId -> set<objects>
        try {
            InputStream csvStream = new FileInputStream(csvPath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream));
            reader.lines().skip(1).forEach(line -> {
                String[] splits = line.split("\\|");
                Integer userId = Integer.valueOf(splits[0]);
                Integer objectId = Integer.valueOf(splits[1]);
                setsPerUser.computeIfAbsent(userId, k -> new HashSet<>()).add(objectId);
            });
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        // update similarities with cardinality of intersection set
        for (int i = 0; i < eigenUserIds.length; i++) {
            HashSet<Integer> eigenSet = setsPerUser.get(eigenUserIds[i]);
            if (eigenSet == null) continue;

            for (HashMap.Entry<Integer, HashSet<Integer>> elem : setsPerUser.entrySet()) {
                Integer userId = elem.getKey();
                if (userId.equals(eigenUserIds[i]) || alreadyKnows.get(i).contains(userId)) continue; // remove self and already-friend

                HashSet<Integer> userSet = elem.getValue();
                userSet.retainAll(eigenSet);
                similarities.get(i).merge(elem.getKey(), userSet.size(), Integer::sum);
            }
        }
    }


    private ArrayList<HashMap<Integer, Integer>> getStaticSimilarities(ArrayList<HashSet<Integer>> alreadyKnows) {
        // init
        ArrayList<HashMap<Integer, Integer>> similarities = new ArrayList<>();
        for (int i = 0; i < eigenUserIds.length; i++) {
            similarities.add(new HashMap<>());
        }

        updateSimilarityWithOneCSV(similarities, alreadyKnows, Config.path_person_hasInterest_tag);
        updateSimilarityWithOneCSV(similarities, alreadyKnows, Config.path_person_isLocatedIn_place);
        updateSimilarityWithOneCSV(similarities, alreadyKnows, Config.path_person_studyAt_organisation);
        updateSimilarityWithOneCSV(similarities, alreadyKnows, Config.path_person_workAt_organisation);

        return similarities;
    }

    private static class CountActivitiesPerUser
            implements AggregateFunction<Activity, HashMap<Integer, Integer>, HashMap<Integer, Integer>> {

        @Override
        public HashMap<Integer, Integer> createAccumulator() {
            return new HashMap<>();  // userId -> count
        }

        @Override
        public HashMap<Integer, Integer> add(Activity value, HashMap<Integer, Integer> accumulator) {
            accumulator.merge(value.getPersonId(), 1, Integer::sum);
            return accumulator;
        }

        @Override
        public HashMap<Integer, Integer> getResult(HashMap<Integer, Integer> accumulator) {
            return accumulator;
        }

        @Override
        public HashMap<Integer, Integer> merge(HashMap<Integer, Integer> r1, HashMap<Integer, Integer> r2) {
            for (HashMap.Entry<Integer, Integer> elem : r1.entrySet()) {
                r2.merge(elem.getKey(), elem.getValue(), Integer::sum);
            }
            return r2;
        }
    }

    public static class GetUserSimilarities
            extends ProcessWindowFunction<HashMap<Integer, Integer>, ArrayList<HashMap<Integer, Integer>>, Integer, TimeWindow> {

        private Integer[] eigenUserIds;
        private ArrayList<HashSet<Integer>> alreadyKnows;

        GetUserSimilarities(ArrayList<HashSet<Integer>> alreadyKnows) {
            this.eigenUserIds = Config.eigenUserIds;
            this.alreadyKnows = alreadyKnows;
        }

        String prettify(TimeWindow w) {
            LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(w.getStart()),
                    TimeZone.getTimeZone("GMT+0").toZoneId());
            LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochMilli(w.getEnd()),
                    TimeZone.getTimeZone("GMT+0").toZoneId());
            return "{" + start + "-" + end + "}";
        }

        @Override
        public void process(Integer postId, Context context, Iterable<HashMap<Integer, Integer>> input, Collector<ArrayList<HashMap<Integer, Integer>>> out) {
            // init similarity matrix
            ArrayList<HashMap<Integer, Integer>> similarities = new ArrayList<>();  // similarities[eigenUsers][allUsers] -> similarity
            for (int i = 0; i < eigenUserIds.length; ++i) {
                similarities.add(new HashMap<>());
            }

            // count activities for every user
            HashMap<Integer, Integer> counts = input.iterator().next();  // userId -> count

            // calculate similarity
            for (int i = 0; i < eigenUserIds.length; ++i) {
                Integer eigenCount = counts.get(eigenUserIds[i]);
                if (eigenCount != null) {
                    for (HashMap.Entry<Integer, Integer> elem : counts.entrySet()) {
                        Integer userId = elem.getKey();
                        if (!userId.equals(eigenUserIds[i]) && !alreadyKnows.get(i).contains(userId)) {
                            // eliminate the already friend users here, so that size of similarities can be reduced (less communication overhead)
                            Integer userCount = elem.getValue();
                            similarities.get(i).put(userId, eigenCount * userCount);
                        }
                    }
                }
            }

            logger.debug("PostId: " + postId + ", Window: " + prettify(context.window()) + ", similarities: " + similarities);
            out.collect(similarities);
        }
    }

    private static class SimilarityAggregate
            implements AggregateFunction<ArrayList<HashMap<Integer, Integer>>, ArrayList<HashMap<Integer, Integer>>, ArrayList<HashMap<Integer, Integer>>> {

        private Integer[] eigenUserIds;

        SimilarityAggregate() { this.eigenUserIds = Config.eigenUserIds; }

        @Override
        public ArrayList<HashMap<Integer, Integer>> createAccumulator() {
            ArrayList<HashMap<Integer, Integer>> accu = new ArrayList<>();  // similarities[eigenUsers][allUsers]
            for (int i = 0; i < eigenUserIds.length; ++i) {
                accu.add(new HashMap<>());
            }
            return accu;
        }

        @Override
        public ArrayList<HashMap<Integer, Integer>> add(ArrayList<HashMap<Integer, Integer>> value, ArrayList<HashMap<Integer, Integer>> accumulator) {
            assert value.size() == accumulator.size();
            for (int i = 0; i < accumulator.size(); ++i) {
                for (HashMap.Entry<Integer, Integer> elem : value.get(i).entrySet()) {
                    accumulator.get(i).merge(elem.getKey(), elem.getValue(), Integer::sum);
                }
            }
            return accumulator;
        }

        @Override
        public ArrayList<HashMap<Integer, Integer>> getResult(ArrayList<HashMap<Integer, Integer>> accumulator) {
            return accumulator;
        }

        @Override
        public ArrayList<HashMap<Integer, Integer>> merge(ArrayList<HashMap<Integer, Integer>> r1, ArrayList<HashMap<Integer, Integer>> r2) {
            return add(r1, r2);
        }
    }

    private static class GetTopFiveRecommendations
            extends ProcessAllWindowFunction<ArrayList<HashMap<Integer, Integer>>, ArrayList<ArrayList<Integer>>, TimeWindow> {

        private Integer[] eigenUserIds;
        ArrayList<HashMap<Integer, Integer>> staticSimilarities;
        Double staticWeight, dynamicWeight;

        GetTopFiveRecommendations(ArrayList<HashMap<Integer, Integer>> staticSimilarities, Double staticWeight) {
            this.eigenUserIds = Config.eigenUserIds;
            this.staticSimilarities = staticSimilarities;
            this.staticWeight = staticWeight;
            this.dynamicWeight = 1.0 - staticWeight;
        }

        String prettify(TimeWindow w) {
            LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(w.getStart()),
                    TimeZone.getTimeZone("GMT+0").toZoneId());
            LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochMilli(w.getEnd()),
                    TimeZone.getTimeZone("GMT+0").toZoneId());
            return "[" + start + ", " + end + ")";
        }

        ArrayList<Tuple2<Double, Double>> getSimilarityRanges(ArrayList<HashMap<Integer, Integer>> similarities) {
            ArrayList<Tuple2<Double, Double>> ret = new ArrayList<>();
            for (HashMap<Integer, Integer> map : similarities) {
                Double lower = Double.POSITIVE_INFINITY, upper = Double.NEGATIVE_INFINITY;
                for (HashMap.Entry<Integer, Integer> elem : map.entrySet()) {
                    Double val = elem.getValue().doubleValue();
                    if (val > upper) upper = val;
                    if (val < lower) lower = val;
                }
                ret.add(new Tuple2<>(lower, upper));
            }
            return ret;
        }

        public void process(Context context,
                            Iterable<ArrayList<HashMap<Integer, Integer>>> aggregations,
                            Collector<ArrayList<ArrayList<Integer>>> out) {

            ArrayList<HashMap<Integer, Integer>> dynamicSimilarities = aggregations.iterator().next();
            ArrayList<Tuple2<Double, Double>> dynamicRanges = getSimilarityRanges(dynamicSimilarities);
            ArrayList<Tuple2<Double, Double>> staticRanges = getSimilarityRanges(staticSimilarities);
            logger.debug("Window: " + prettify(context.window()) + ", dynamicSimilarities: " + dynamicSimilarities);
            logger.debug("Window: " + prettify(context.window()) + ", staticSimilarities: " + staticSimilarities);

            ArrayList<ArrayList<Integer>> recommendations = new ArrayList<>();

            for (int i = 0; i < eigenUserIds.length; i++) {
                // calculate final similarity and sort
                Tuple2<Double, Double> staticRange = staticRanges.get(i);
                Tuple2<Double, Double> dynamicRange = dynamicRanges.get(i);

                Double staticMin = staticRange.f0;
                Double staticSpan = staticRange.f1 - staticRange.f0;
                Double dynamicMin = dynamicRange.f0;
                Double dynamicSpan = dynamicRange.f1 - dynamicRange.f0;

                PriorityQueue<UserWithSimilarity> queue = new PriorityQueue<>(new UserWithSimilarityComparator());
                // for all users that have static similarity with eigen-user i
                for (HashMap.Entry<Integer, Integer> elem : staticSimilarities.get(i).entrySet()) {
                    Integer userId = elem.getKey();
                    Integer staticVal = elem.getValue();
                    Integer dynamicVal = dynamicSimilarities.get(i).getOrDefault(userId, 0);
                    dynamicSimilarities.get(i).remove(userId); // remove users that have both static and dynamic similarities with eigen-user i

                    Double staticPart = (staticSpan > 0.0) ? ((staticVal - staticMin) / staticSpan) : 1.0;
                    Double dynamicPart = (dynamicSpan > 0.0) ? ((dynamicVal - dynamicMin) / dynamicSpan) : 1.0;
                    queue.offer(new UserWithSimilarity(userId, staticPart * staticWeight + dynamicPart * dynamicWeight));
                }
                // the remaining users have dynamic similarity with eigen-user i, but no static similarity
                for (HashMap.Entry<Integer, Integer> elem : dynamicSimilarities.get(i).entrySet()) {
                    Integer userId = elem.getKey();
                    Integer dynamicVal = elem.getValue();
                    queue.offer(new UserWithSimilarity(userId, ((dynamicSpan > 0.0) ? ((dynamicVal - dynamicMin) / dynamicSpan) : 1.0) * dynamicWeight));
                }

                // get top 5
                ArrayList<Integer> recommendationsPerUser = new ArrayList<>();
                while (!queue.isEmpty() && recommendationsPerUser.size() < 5) {
                    UserWithSimilarity pair = queue.poll();
                    recommendationsPerUser.add(pair.userId);
                    logger.debug("Window: " + prettify(context.window()) + ", recommend for " + eigenUserIds[i] + ": " + pair);
                }
                recommendations.add(recommendationsPerUser);
            }

            out.collect(recommendations);
        }
    }

    public static class UserWithSimilarity {
        Integer userId;
        Double similarity;

        UserWithSimilarity(Integer userId, Double similarity) { this.userId = userId; this.similarity = similarity; }

        @Override
        public String toString() {
            return "(userId: " + userId + ", similarity: " + similarity + ")";
        }
    }

    public static class UserWithSimilarityComparator implements Comparator<UserWithSimilarity> {
        // for descending order of similarity
        public int compare(UserWithSimilarity s1, UserWithSimilarity s2) {
            if (s1.similarity < s2.similarity)
                return 1;
            else if (s1.similarity > s2.similarity)
                return -1;
            return 0;
        }
    }

}


/*
-------------------------------
Test stream: data/task2.txt
-------------------------------
Post,1,10000,2019-05-01 08:00:00  // TODO change time span to have meaningful output for 4hr/1hr
Post,2,10001,2019-05-01 08:30:22
Comment,1,10000,2019-05-01 08:35:40
Comment,1,10010,2019-05-01 09:00:09
Comment,2,10000,2019-05-01 10:00:10
Like,1,10001,2019-05-01 13:59:59
Comment,1,10001,2019-05-01 15:22:33
Comment,2,10010,2019-05-02 03:00:50
Like,1,10000,2019-05-03 12:00:35
Like,2,10001,2019-05-04 19:00:33

------------------------------------
Test table: person_knows_person.csv
------------------------------------
Person.id|Person.id
10000|10001
10001|10010

---------------------------------------
Test table: person_hasInterest_tag.csv
---------------------------------------
Person.id|Tag.id
10000|1
10000|2
10000|3
10000|4
10001|4
10001|3
10010|3
10011|4

---------------------------------------
Test table: person_isLocatedIn_place.csv
---------------------------------------
Person.id|Place.id
10000|1
10000|2
10000|3
10000|4
10001|4
10001|3
10010|3
10011|4

--------------------------------------------
Test table: person_studyAt_organisation.csv
--------------------------------------------
Person.id|Organisation.id|classYear
10000|1|2020
10000|2|2018
10000|3|2018
10000|4|2014
10001|4|2013
10001|3|2010
10010|3|2015
10011|4|1998

--------------------------------------------
Test table: person_workAt_organisation.csv
--------------------------------------------
Person.id|Organisation.id|workFrom
10000|1|2020
10000|2|2018
10000|3|2018
10000|4|2014
10001|4|2013
10001|3|2010
10010|3|2015
10011|4|1998
*/