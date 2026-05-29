package com.sapari.live.domain.repository;

import java.util.List;

import com.sapari.live.domain.model.LiveProduct;

public interface LiveProductRepository {

    void saveAll(List<LiveProduct> products);
}
