package io.renova.core.spi;

import io.renova.core.playbook.Rule;

/**
 * Creates detectors for one {@code detect.type} used in playbooks. Factories validate parameters
 * when the detector is created so a bad playbook fails before scanning starts.
 */
public interface DetectorFactory {

    String type();

    Detector create(Rule rule);
}
