package com.prompthub.search.application.query;

import java.util.List;

public record ProductSearchPageResult(List<ProductSearchHit> hits, long total) {
}
