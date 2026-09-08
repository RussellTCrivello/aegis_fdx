package com.aegis.fdx.engine;

import com.aegis.fdx.model.Item;

/**
 * A-03 / 3.1: every engine→UI notification. The UI never polls the engine; it
 * subscribes to this stream and marshals onto the FX thread.
 */
public sealed interface EngineEvent {

    record Started(String caseName, long plannedItems) implements EngineEvent {}

    record ItemIndexed(Item item, long done, long total) implements EngineEvent {}

    record Progress(long done, long total, double itemsPerSecond,
                    double cpuLoad, long heapUsedMb, int activeWorkers) implements EngineEvent {}

    record Log(String level, String message) implements EngineEvent {}

    record Failed(String itemId, String itemName, String reason) implements EngineEvent {}

    record Paused() implements EngineEvent {}

    record Resumed() implements EngineEvent {}

    record Finished(long indexed, long errors, long locked, long unsupported,
                    long durationMillis) implements EngineEvent {}
}
