package com.mbaliga.alloy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic, bounded XY plate packer for already-oriented mesh parts.
 *
 * The planner deliberately limits automatic changes to a 0/90 degree Z
 * rotation. It never invents an upright or tilted print orientation: those
 * decisions can change support needs and first-layer behavior and remain an
 * explicit user choice. Within that boundary, a best-fit shelf pack makes
 * repeated phone-side arrangement stable and gives the caller a proof that
 * every returned rectangle fits the reviewed build plate.
 */
public final class PlateArrangementPlanner {
    private static final float EPSILON = 0.0001f;
    private static final int MAX_PARTS = 64;

    private PlateArrangementPlanner() { }

    public static final class Part {
        public final int sourceIndex;
        public final float width;
        public final float depth;

        public Part(int sourceIndex, float width, float depth) {
            this.sourceIndex = sourceIndex;
            this.width = width;
            this.depth = depth;
        }
    }

    public static final class Placement {
        public final int sourceIndex;
        /** Lower-left point of the packed XY rectangle, in bed coordinates. */
        public final float x;
        public final float y;
        public final float width;
        public final float depth;
        /** Additional Z rotation chosen by the planner: 0 or 90 degrees. */
        public final float rotationDegrees;

        private Placement(int sourceIndex, float x, float y,
                          float width, float depth, float rotationDegrees) {
            this.sourceIndex = sourceIndex;
            this.x = x;
            this.y = y;
            this.width = width;
            this.depth = depth;
            this.rotationDegrees = rotationDegrees;
        }

        public float maxX() { return x + width; }
        public float maxY() { return y + depth; }
    }

    /**
     * Pack all parts or throw if the reviewed bed cannot contain them.
     * Returned placements are in source-index order, which makes applying
     * them to a MeshModel safe even when the planner internally sorts by area.
     */
    public static ArrayList<Placement> plan(List<Part> input,
                                            float bedWidth, float bedDepth,
                                            float clearanceMm) {
        checkFinitePositive(bedWidth, "bed width");
        checkFinitePositive(bedDepth, "bed depth");
        checkFiniteNonNegative(clearanceMm, "clearance");
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("No parts to arrange");
        if (input.size() > MAX_PARTS) throw new IllegalArgumentException("Too many parts to arrange");

        ArrayList<Part> sorted = new ArrayList<>(input.size());
        boolean[] seen = new boolean[MAX_PARTS];
        for (Part part : input) {
            if (part == null || part.sourceIndex < 0 || part.sourceIndex >= MAX_PARTS)
                throw new IllegalArgumentException("Part index is invalid");
            if (seen[part.sourceIndex]) throw new IllegalArgumentException("Duplicate part index");
            seen[part.sourceIndex] = true;
            checkFinitePositive(part.width, "part width");
            checkFinitePositive(part.depth, "part depth");
            if (!fits(part.width, part.depth, bedWidth, bedDepth)
                    && !fits(part.depth, part.width, bedWidth, bedDepth))
                throw new IllegalArgumentException("Part " + part.sourceIndex + " is larger than the available build plate");
            sorted.add(part);
        }
        Collections.sort(sorted, Comparator
                .comparingDouble((Part part) -> -((double) part.width * part.depth))
                .thenComparingDouble(part -> -Math.max(part.width, part.depth))
                .thenComparingInt(part -> part.sourceIndex));

        ArrayList<Shelf> shelves = new ArrayList<>();
        ArrayList<Placement> placed = new ArrayList<>(input.size());
        float packedDepth = 0f;
        for (Part part : sorted) {
            Candidate best = null;
            for (int shelfIndex = 0; shelfIndex < shelves.size(); shelfIndex++) {
                Shelf shelf = shelves.get(shelfIndex);
                best = chooseBetter(best, candidateForShelf(part, shelf, shelfIndex,
                        bedWidth, bedDepth));
            }
            float newShelfY = packedDepth == 0f ? 0f : packedDepth + clearanceMm;
            Candidate newShelf = candidateForNewShelf(part, shelves.size(), newShelfY,
                    bedWidth, bedDepth);
            best = chooseBetter(best, newShelf);
            if (best == null)
                throw new IllegalArgumentException("The parts do not fit together on the build plate");

            Shelf shelf;
            if (best.newShelf) {
                shelf = new Shelf(best.y, best.depth, best.x + best.width + clearanceMm);
                shelves.add(shelf);
            } else {
                shelf = shelves.get(best.shelfIndex);
                shelf.nextX = best.x + best.width + clearanceMm;
                shelf.height = Math.max(shelf.height, best.depth);
            }
            packedDepth = Math.max(packedDepth, shelf.y + shelf.height);
            placed.add(new Placement(part.sourceIndex, best.x, best.y,
                    best.width, best.depth, best.rotationDegrees));
        }

        Collections.sort(placed, Comparator.comparingInt(value -> value.sourceIndex));
        return placed;
    }

    private static Candidate candidateForShelf(Part part, Shelf shelf, int shelfIndex,
                                                float bedWidth, float bedDepth) {
        Candidate best = null;
        best = chooseBetter(best, candidate(part, shelf.nextX, shelf.y,
                part.width, part.depth, 0f, shelfIndex, false,
                shelf.height, bedWidth, bedDepth));
        if (Math.abs(part.width - part.depth) > EPSILON) {
            best = chooseBetter(best, candidate(part, shelf.nextX, shelf.y,
                    part.depth, part.width, 90f, shelfIndex, false,
                    shelf.height, bedWidth, bedDepth));
        }
        return best;
    }

    private static Candidate candidateForNewShelf(Part part, int shelfIndex, float y,
                                                   float bedWidth, float bedDepth) {
        Candidate best = null;
        best = chooseBetter(best, candidate(part, 0f, y,
                part.width, part.depth, 0f, shelfIndex, true,
                0f, bedWidth, bedDepth));
        if (Math.abs(part.width - part.depth) > EPSILON) {
            best = chooseBetter(best, candidate(part, 0f, y,
                    part.depth, part.width, 90f, shelfIndex, true,
                    0f, bedWidth, bedDepth));
        }
        return best;
    }

    private static Candidate candidate(Part part, float x, float y,
                                       float width, float depth, float rotation,
                                       int shelfIndex, boolean newShelf,
                                       float currentShelfHeight,
                                       float bedWidth, float bedDepth) {
        if (x < -EPSILON || y < -EPSILON
                || x + width > bedWidth + EPSILON
                || y + depth > bedDepth + EPSILON) return null;
        float top = y + Math.max(currentShelfHeight, depth);
        return new Candidate(part.sourceIndex, x, y, width, depth, rotation,
                shelfIndex, newShelf, top, bedWidth - (x + width));
    }

    private static Candidate chooseBetter(Candidate current, Candidate next) {
        if (next == null) return current;
        if (current == null) return next;
        if (next.top < current.top - EPSILON) return next;
        if (next.top > current.top + EPSILON) return current;
        if (next.remainingWidth < current.remainingWidth - EPSILON) return next;
        if (next.remainingWidth > current.remainingWidth + EPSILON) return current;
        if (next.y < current.y - EPSILON) return next;
        if (next.y > current.y + EPSILON) return current;
        if (next.x < current.x - EPSILON) return next;
        if (next.x > current.x + EPSILON) return current;
        if (next.rotationDegrees < current.rotationDegrees) return next;
        return current;
    }

    private static boolean fits(float width, float depth, float bedWidth, float bedDepth) {
        return width <= bedWidth + EPSILON && depth <= bedDepth + EPSILON;
    }

    private static void checkFinitePositive(float value, String label) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value <= 0f)
            throw new IllegalArgumentException(label + " must be finite and positive");
    }

    private static void checkFiniteNonNegative(float value, String label) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0f)
            throw new IllegalArgumentException(label + " must be finite and non-negative");
    }

    private static final class Shelf {
        final float y;
        float height;
        float nextX;

        Shelf(float y, float height, float nextX) {
            this.y = y;
            this.height = height;
            this.nextX = nextX;
        }
    }

    private static final class Candidate {
        final int sourceIndex;
        final float x, y, width, depth, rotationDegrees;
        final int shelfIndex;
        final boolean newShelf;
        final float top, remainingWidth;

        Candidate(int sourceIndex, float x, float y, float width, float depth,
                  float rotationDegrees, int shelfIndex, boolean newShelf,
                  float top, float remainingWidth) {
            this.sourceIndex = sourceIndex;
            this.x = x;
            this.y = y;
            this.width = width;
            this.depth = depth;
            this.rotationDegrees = rotationDegrees;
            this.shelfIndex = shelfIndex;
            this.newShelf = newShelf;
            this.top = top;
            this.remainingWidth = remainingWidth;
        }
    }
}
