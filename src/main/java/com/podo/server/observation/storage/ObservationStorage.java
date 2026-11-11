package com.podo.server.observation.storage;

import java.time.Instant;
import java.util.List;

public interface ObservationStorage {

    void write(String key, byte[] bytes);

    byte[] read(String key);

    void delete(String key);

    List<String> orphanCandidates(Instant olderThan);
}