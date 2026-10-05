package io.renova.core.spi;

import io.renova.core.model.Finding;
import io.renova.core.scan.ScanContext;

import java.util.List;

/** Finds the places in a project where one rule applies. */
@FunctionalInterface
public interface Detector {
    List<Finding> detect(ScanContext context);
}
