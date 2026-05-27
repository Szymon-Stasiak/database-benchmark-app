package com.dbagnets.backend.insert.strategy;

import com.dbagnets.backend.insert.datagen.GeneratedRecord;

import java.util.List;

/**
 * One slice of work pulled off the per-DB queue: a contiguous range of generated records,
 * with the batch's index inside its parent run for progress reporting.
 */
public record Batch(int index, int total, List<GeneratedRecord> records) {
    public Batch {
        records = List.copyOf(records);
    }

    public int size() {
        return records.size();
    }
}
