package com.prompthub.search.application;

import java.util.List;

public record ProductSearchPageResult(List<ProductSearchHit> hits, long total) {
}
