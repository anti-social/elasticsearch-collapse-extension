/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package dev.evo.elasticsearch.collapse.rescore;

import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.search.rescore.RescoreContext;
import org.elasticsearch.search.rescore.Rescorer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;

public class CollapseRescorer implements Rescorer {

    private static final CollapseRescorer INSTANCE = new CollapseRescorer();

    private static final Comparator<ScoreDoc> DOC_COMPARATOR = Comparator.comparingInt(d -> d.doc);

    private static final Comparator<ScoreDoc> SCORE_DOC_COMPARATOR = (a, b) -> {
        if (a.score > b.score) {
            return -1;
        }
        else if (a.score < b.score) {
            return 1;
        }
        return a.doc - b.doc;
    };

    static class Context extends RescoreContext {
        final IndexFieldData<?> groupField;
        final int shardSize;
        final Sort sort;

        Context(int windowSize, IndexFieldData<?> groupField, int shardSize, Sort sort) {
            super(windowSize, INSTANCE);
            this.groupField = groupField;
            this.shardSize = shardSize;
            this.sort = sort;
        }
    }

    static class CollapsedScoreDoc extends ScoreDoc {
        int slot;

        CollapsedScoreDoc(ScoreDoc hit, int slot) {
            super(hit.doc, hit.score, hit.shardIndex);
            this.slot = slot;
        }

        @Override
        public String toString() {
            return String.format(
                Locale.ENGLISH,
                "CollapsedScoreDoc<doc = %s, score = %s, slot = %s>",
                doc, score, slot
            );
        }
    }

    @Override
    public TopDocs rescore(
        TopDocs topDocs, IndexSearcher searcher, RescoreContext rescoreContext
    ) throws IOException {
        final var ctx = (Context) rescoreContext;
        if (topDocs == null || topDocs.totalHits.value == 0 || topDocs.scoreDocs.length == 0) {
            return topDocs;
        }

        final var hits = topDocs.scoreDocs;

        final var size = Math.min(ctx.getWindowSize(), hits.length);
        if (size <= 0) {
            return topDocs;
        }

        Arrays.sort(hits, DOC_COMPARATOR);

        final var readerContexts = searcher.getIndexReader().leaves();
        var currentReaderIx = -1;
        var currentReaderEndDoc = 0;
        var currentReaderContext = readerContexts.get(0);
        var groupValues = ctx.groupField
            .load(currentReaderContext)
            .getBytesValues();

        final var sortFields = ctx.sort.getSort();
        final var sortField = sortFields[0];
        final var reverseMul = sortField.getReverse() ? -1 : 1;
        final var comparator = sortField.getComparator(hits.length, 0);
        final var docScorer = new Scorable() {
            private int doc;
            private float score;

            public void setDoc(int doc) {
                this.doc = doc;
            }

            public void setScore(float score) {
                this.score = score;
            }

            @Override
            public int docID() {
                return doc;
            }

            @Override
            public float score() {
                return score;
            }
        };
        var leafComparator = comparator.getLeafComparator(currentReaderContext);
        leafComparator.setScorer(docScorer);

        final var collapsedHits = new ArrayList<CollapsedScoreDoc>(size);

        // Stores the most relevant hit data for every group
        final var groupTops = new HashMap<BytesRef, CollapsedScoreDoc>();

        var slot = -1;
        for (var hit : hits) {
            slot++;

            final var prevReaderContext = currentReaderContext;

            // find segment that contains current document
            while (hit.doc >= currentReaderEndDoc) {
                currentReaderIx++;
                currentReaderContext = readerContexts.get(currentReaderIx);
                currentReaderEndDoc = currentReaderContext.docBase +
                    currentReaderContext.reader().maxDoc();
            }

            final int docId = hit.doc - currentReaderContext.docBase;
            if (currentReaderContext != prevReaderContext) {
                leafComparator = comparator.getLeafComparator(currentReaderContext);
                leafComparator.setScorer(docScorer);
                groupValues = ctx.groupField
                    .load(currentReaderContext)
                    .getBytesValues();
            }

            docScorer.setDoc(docId);
            docScorer.setScore(hit.score);
            leafComparator.copy(slot, docId);
            final var sortValue = comparator.value(slot);

            if (groupValues.advanceExact(docId)) {
                final var groupValue = groupValues.nextValue();
                final var top = groupTops.get(groupValue);

                if (top == null) {
                    // There is no top document for a group value so
                    // install it
                    final var scoreDoc = new CollapsedScoreDoc(hit, slot);
                    collapsedHits.add(scoreDoc);
                    groupTops.put(
                        BytesRef.deepCopyOf(groupValue), scoreDoc
                    );
                } else {
                    leafComparator.setBottom(top.slot);
                    if (reverseMul * leafComparator.compareBottom(docId) > 0) {
                        // New document is more competitive, replace top document in a group
                        top.doc = hit.doc;
                        top.slot = slot;
                    }
                    if (hit.score > top.score) {
                        // Elasticsearch requires scores to be non-decreasing
                        // Replace top document's score if new score is greater then current
                        top.score = hit.score;
                    }
                }
            } else {
                // A document doesn't have group value so
                // just add it to collapsed hits list
                final var scoreDoc = new CollapsedScoreDoc(hit, slot);
                collapsedHits.add(scoreDoc);
            }
        }

        collapsedHits.sort(SCORE_DOC_COMPARATOR);

        final var numHits = Math.min(collapsedHits.size(), ctx.shardSize);
        final var trimmedHits = collapsedHits.stream()
            .limit(numHits)
            // Elasticsearch requires only `ScoreDoc` objects in `TopDocs`.
            // It would be nice to find a way to pass `FieldDoc`s here
            // but it is not possible at the moment
            // as it requires also to pass `DocValueFormat[]` somehow
            .map(doc -> new ScoreDoc(doc.doc, doc.score, doc.shardIndex))
            .toArray(ScoreDoc[]::new);
        return new TopDocs(
            topDocs.totalHits, trimmedHits
        );
    }

    @Override
    public Explanation explain(
        int topLevelDocId,
        IndexSearcher searcher,
        RescoreContext rescoreContext,
        Explanation sourceExplanation
    ) {
        // TODO: explain that score of the collapsed hit could be borrowed
        return sourceExplanation;
    }
}
