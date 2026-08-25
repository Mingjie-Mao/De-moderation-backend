package com.campusguard.moderation.engine;

import java.util.List;

/**
 * A group of engines whose number is decided by configuration rather than by the
 * code.
 *
 * <p>Spring collects engines declared one per class automatically. It cannot
 * collect a list whose length is a property, and registering singletons by hand
 * would make the registry depend on bean creation order. Wrapping them in one
 * bean of a distinct type keeps the wiring explicit and order-independent.
 */
public record ModerationEngineBundle(List<ModerationEngine> engines) {
}
