package edu.umn.cs.recsys.ii;

import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import org.grouplens.lenskit.collections.LongUtils;
import org.grouplens.lenskit.core.Transient;
import org.grouplens.lenskit.cursors.Cursor;
import org.grouplens.lenskit.data.dao.ItemDAO;
import org.grouplens.lenskit.data.dao.UserEventDAO;
import org.grouplens.lenskit.data.event.Event;
import org.grouplens.lenskit.data.history.RatingVectorUserHistorySummarizer;
import org.grouplens.lenskit.data.history.UserHistory;
import org.grouplens.lenskit.scored.*;
import org.grouplens.lenskit.vectors.ImmutableSparseVector;
import org.grouplens.lenskit.vectors.MutableSparseVector;
import org.grouplens.lenskit.vectors.SparseVector;
import org.grouplens.lenskit.vectors.VectorEntry;
import org.grouplens.lenskit.vectors.similarity.CosineVectorSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.util.*;

/**
 * @author <a href="http://www.grouplens.org">GroupLens Research</a>
 */
public class SimpleItemItemModelBuilder implements Provider<SimpleItemItemModel> {
    private final ItemDAO itemDao;
    private final UserEventDAO userEventDao;
    private static final Logger logger = LoggerFactory.getLogger(SimpleItemItemModelBuilder.class);;

    @Inject
    public SimpleItemItemModelBuilder(@Transient ItemDAO idao,
                                      @Transient UserEventDAO uedao) {
        itemDao = idao;
        userEventDao = uedao;
    }

    @Override
    public SimpleItemItemModel get() {
        // Get the transposed rating matrix
        // This gives us a map of item IDs to those items' rating vectors
        Map<Long, ImmutableSparseVector> itemVectors = getItemVectors();

        // Get all items - you might find this useful
        LongSortedSet items = LongUtils.packedSet(itemVectors.keySet());
        // Map items to vectors of item similarities
        Map<Long,MutableSparseVector> itemSimilarities = new HashMap<Long, MutableSparseVector>();

        Map<Long,List<ScoredId>> returnMap = new HashMap<Long, List<ScoredId>>();
        // TODO Compute the similarities between each pair of items
        for(Map.Entry<Long, ImmutableSparseVector> entry:itemVectors.entrySet()){
            MutableSparseVector simVector = MutableSparseVector.create(items,0);
            ScoredIdListBuilder scoreListBuilder = new ScoredIdListBuilder();
            long itemId = entry.getKey();  // base itemId
            ImmutableSparseVector itemVector = entry.getValue(); // base item vector
            for(Map.Entry<Long, ImmutableSparseVector> compareEntry:itemVectors.entrySet()){
                long compareItemId = compareEntry.getKey();
                ImmutableSparseVector compareItemVector = compareEntry.getValue();
                ScoredIdBuilder builder = new ScoredIdBuilder(compareItemId,0);
                // Calculate the similarity between itemVector and compareItemVector
                double sim = new CosineVectorSimilarity().similarity(itemVector, compareItemVector);
                // Store it into itemSimilarities
                if(sim > 0){
                    simVector.set(compareItemId,sim);
                    builder.setScore(sim);
                }
                scoreListBuilder.add(builder.build());
            }
            //
            itemSimilarities.put(itemId, simVector);
            // Need to sort the list inside soreListBuilder....
            MyScoredIdComparator comp = new MyScoredIdComparator();

            // scoreListBuilder.sort(comp).finish()
            PackedScoredIdList scoredIdList = scoreListBuilder.sort(comp).finish();// scoreListBuilder.finish();
            returnMap.put(itemId, scoredIdList);
        }


        // It will need to be in a map of longs to lists of Scored IDs to store in the model

        //return new SimpleItemItemModel(Collections.EMPTY_MAP);
        return new SimpleItemItemModel(returnMap);
    }

    /**
     * Load the data into memory, indexed by item.
     * @return A map from item IDs to item rating vectors. Each vector contains users' ratings for
     * the item, keyed by user ID.
     */
    public Map<Long,ImmutableSparseVector> getItemVectors() {
        // set up storage for building each item's rating vector
        LongSet items = itemDao.getItemIds();
        // map items to maps from users to ratings
        Map<Long,Map<Long,Double>> itemData = new HashMap<Long, Map<Long, Double>>();
        for (long item: items) {
            itemData.put(item, new HashMap<Long, Double>());
        }
        // itemData should now contain a map to accumulate the ratings of each item

        // stream over all user events.
        Cursor<UserHistory<Event>> stream = userEventDao.streamEventsByUser();
        try {
            for (UserHistory<Event> evt: stream) {
                // Each event consist of the user and all his ratings. A rating is an item-value pair.
                // Here, we summarize all the events of a given user into a vector.
                MutableSparseVector vector = RatingVectorUserHistorySummarizer.makeRatingVector(evt).mutableCopy();
                long userId = evt.getUserId(); // userId
                // vector is now the user's rating vector
                // TODO Normalize this vector and store the ratings in the item data
                double meanRating = vector.mean();
                for(VectorEntry e:vector.fast()){
                    long itemId = e.getKey(); // itemId
                    double rating = e.getValue(); // rating
                    rating = rating-meanRating;
                    //vector.set(itemId,rating);
                    itemData.get(itemId).put(userId,rating);
                }
            }
        } finally {
            stream.close();
        }

        // This loop converts our temporary item storage to a map of item vectors
        Map<Long,ImmutableSparseVector> itemVectors = new HashMap<Long, ImmutableSparseVector>();
        for (Map.Entry<Long,Map<Long,Double>> entry: itemData.entrySet()) {
            MutableSparseVector vec = MutableSparseVector.create(entry.getValue());
            itemVectors.put(entry.getKey(), vec.immutable());
        }
        return itemVectors;
    }
}

class MyScoredIdComparator implements Comparator<ScoredId> {
    @Override
    public int compare(ScoredId o1, ScoredId o2) {
        return Double.compare(o2.getScore(),o1.getScore() ); // for non-increasing order
    }
}