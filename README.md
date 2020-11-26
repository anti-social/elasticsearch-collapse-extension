## What does this plugin allows to do?

You can collapse search results based on field values:

```yaml
ext:
  collapse:
    field: model_id
    window_size: 10000  # window for collapsing
    shard_size: 1000  # truncate after collapsing on a shard
    sort:
      price: asc
```

Or using a script to choose the best hit in a group:
```yaml
ext:
  collapse:
    field: model_id
    window_size: 10000
    shard_size: 1000
    sort:
      _script:
        type: number
        script:
          lang: painless
          source: |
            float base = 0.0;
            float dev = randomScore(params.seed, '_seq_no') * 0.1;
            if (doc['grade'].value < 4) {
                base = 1000.0;
            }
            float price = doc['price'].value;           
            return base + Math.log1p(price * (1.0 + dev));
          params:
            seed: 123456  # seed из сессии
        order: asc
```

## Why not use existing solutions?

There are 2 solution for collapsing out of the box but they both have some drawbacks:

### [Collapse](https://www.elastic.co/guide/en/elasticsearch/reference/7.9/collapse-search-results.html)

Firstly it collapses all the documents that don't have value for collapsing into a single hit.
This can be bypassed populating some random value for such documents.

And what's more important it does not support rescoring which we use a lot for deboosting products from the same company:

> collapse cannot be used in conjunction with 
[scroll](https://www.elastic.co/guide/en/elasticsearch/reference/7.9/paginate-search-results.html#scroll-search-results), 
[rescore](https://www.elastic.co/guide/en/elasticsearch/reference/current/filter-search-results.html#rescore) or 
[search after](https://www.elastic.co/guide/en/elasticsearch/reference/current/paginate-search-results.html#search-after).

### [Top hits aggregation](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-metrics-top-hits-aggregation.html)

This kind of aggregation is not recommended to use for collapsing:

> We do not recommend using top_hits as a top-level aggregation. If you want to group search hits, use the collapse parameter instead.

Also we need to populute documents with some random values.

Although top hits aggregation calls rescore phase, its parent aggregation doesn't. So we cannot sort groups using rescored score.
