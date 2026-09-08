package com.aegis.fdx.engine;

import com.aegis.fdx.model.Item;

import java.util.List;

/** One row in the result grid (F-17), carrying highlight fragments. */
public record SearchHit(Item item, float score, int hitCount, List<String> fragments) {}
