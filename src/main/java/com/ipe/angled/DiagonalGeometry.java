package com.ipe.angled;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * All of the 45 degree maths in one place.
 *
 * <p>A diagonal painting is a normal Immersive Paintings entity whose {@code rotation} field holds
 * {@code +45} or {@code -45} instead of a multiple of 90. That field is already persisted to NBT and
 * sent to clients in {@code PaintingModifyMessage}, so nothing extra has to be synced.</p>
 *
 * <p>The painting itself is a vertical rectangle. Its back plane is tilted 45 degrees around the
 * Y axis and is chosen so that it only <em>touches</em> the clicked block along one vertical edge,
 * which is exactly the "diagonal wall" look: the picture rests on the corners of the stairs and
 * never cuts into a block.</p>
 */
public final class DiagonalGeometry {

    /** The only supported offset from the cardinal facing, in degrees. */
    public static final int DELTA = 45;

    /** Half the thickness of the canvas, in blocks (Immersive Paintings uses 1/32). */
    public static final double HALF_THICKNESS = 1.0 / 32.0;

    /**
     * Extra room around the canvas for crosshair picking. The canvas itself is only 1/16 block
     * thin, so a small margin keeps edge clicks forgiving without bringing back the oversized box.
     */
    public static final double PICK_MARGIN = 0.03;

    private static final double EPSILON = 1.0e-6;
    private static final double SLAB_EPSILON = 1.0e-9;

    private DiagonalGeometry() {
    }

    /** A vertical edge of a block, given by its horizontal coordinates. */
    public record Corner(double x, double z) {
    }

    public static boolean isDiagonal(int rotation) {
        return Math.floorMod(rotation, 360) % 90 != 0;
    }

    public static boolean isDiagonal(Direction facing, int rotation) {
        return facing != null && facing.getAxis().isHorizontal() && isDiagonal(rotation);
    }

    /**
     * Entity yaw that makes the rendered canvas face the diagonal direction. Immersive Paintings
     * derives the yaw of a wall painting from {@code facing.getHorizontal() * 90}, so we simply add
     * the offset. The renderer rotates by {@code -yaw}, which is why a plain yaw change is enough.
     */
    public static float yaw(Direction facing, int rotation) {
        return facing.getHorizontal() * 90.0f + rotation;
    }

    /** Horizontal normal of the painting plane as {@code {x, z}}. */
    public static double[] normal(Direction facing, int rotation) {
        double radians = Math.toRadians(yaw(facing, rotation));
        return new double[]{-Math.sin(radians), Math.cos(radians)};
    }

    /**
     * The vertical edge the painting plane rests on, or {@code null} when the given rotation does
     * not describe a diagonal painting (or the attachment block is unknown).
     *
     * <p>Of the two vertical edges of the clicked face exactly one can be touched by a plane with
     * the given normal without slicing through the block, so we simply test both and keep the one
     * that leaves the whole block behind the plane.</p>
     */
    public static Corner corner(BlockPos attachmentPos, Direction facing, int rotation) {
        if (attachmentPos == null || !isDiagonal(facing, rotation)) {
            return null;
        }

        BlockPos support = attachmentPos.offset(facing.getOpposite());
        double[] normal = normal(facing, rotation);

        // Axis along the clicked face, perpendicular to the face normal.
        double axisX = facing.getOffsetZ();
        double axisZ = -facing.getOffsetX();

        // Centre of the clicked face (the Y coordinate is irrelevant here).
        double centreX = support.getX() + 0.5 + facing.getOffsetX() * 0.5;
        double centreZ = support.getZ() + 0.5 + facing.getOffsetZ() * 0.5;

        for (int sign = -1; sign <= 1; sign += 2) {
            double x = centreX + axisX * 0.5 * sign;
            double z = centreZ + axisZ * 0.5 * sign;
            if (keepsBlockBehind(support, x, z, normal)) {
                return new Corner(x, z);
            }
        }
        return null;
    }

    /**
     * True when every corner of the block is on the inner side of the plane through
     * {@code (x, z)} with the given normal, i.e. the plane does not cut the block.
     */
    private static boolean keepsBlockBehind(BlockPos block, double x, double z, double[] normal) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= 1; i++) {
            for (int j = 0; j <= 1; j++) {
                double dx = block.getX() + i - x;
                double dz = block.getZ() + j - z;
                max = Math.max(max, dx * normal[0] + dz * normal[1]);
            }
        }
        return max <= EPSILON;
    }

    /**
     * Picks {@code +45} or {@code -45} so that the touched edge is the one the player aimed at,
     * which lets players choose either diagonal by clicking the left or right half of a face.
     */
    public static int chooseRotation(BlockPos attachmentPos, Direction facing, double hitX, double hitZ) {
        int best = DELTA;
        double bestDistance = Double.MAX_VALUE;

        for (int delta : new int[]{DELTA, -DELTA}) {
            Corner corner = corner(attachmentPos, facing, delta);
            if (corner == null) {
                continue;
            }
            double dx = corner.x() - hitX;
            double dz = corner.z() - hitZ;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = delta;
            }
        }
        return best;
    }

    /**
     * Precise ray test against the tilted canvas.
     *
     * <p>The entity's bounding box has to be axis aligned and therefore covers the whole diagonal,
     * which would make anything inside that box unclickable - including blocks the player is
     * actually aiming at. This test uses the real (rotated, thin) rectangle instead so that the
     * crosshair only snaps to the painting when it visually covers the pixel under it.</p>
     *
     * <p>{@code center} is the entity position, which is also the centre of the canvas slab.</p>
     *
     * @return the ray parameter in {@code [0, 1]} where the canvas is entered, or {@link Double#NaN}
     *         when the ray misses it
     */
    public static double canvasRayHit(Vec3d start, Vec3d end, Vec3d center, double yawDegrees,
                                      double halfWidth, double halfHeight) {
        double radians = Math.toRadians(yawDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        // Bring the ray into the canvas' local frame: x = tangent, y = up, z = normal.
        double vx = start.x - center.x;
        double vz = start.z - center.z;
        double vdx = end.x - start.x;
        double vdz = end.z - start.z;

        double originX = vx * cos + vz * sin;
        double originZ = -vx * sin + vz * cos;
        double directionX = vdx * cos + vdz * sin;
        double directionZ = -vdx * sin + vdz * cos;
        double originY = start.y - center.y;
        double directionY = end.y - start.y;

        double[] range = clip(originZ, directionZ, HALF_THICKNESS + PICK_MARGIN, 0.0, 1.0);
        if (range == null) {
            return Double.NaN;
        }
        range = clip(originX, directionX, halfWidth + PICK_MARGIN, range[0], range[1]);
        if (range == null) {
            return Double.NaN;
        }
        range = clip(originY, directionY, halfHeight + PICK_MARGIN, range[0], range[1]);
        return range == null ? Double.NaN : range[0];
    }

    /**
     * One axis of a slab test. Returns the narrowed {@code [tMin, tMax]} range, or {@code null} when
     * the ray never enters the slab.
     */
    private static double[] clip(double origin, double direction, double halfExtent, double tMin, double tMax) {
        if (Math.abs(direction) < SLAB_EPSILON) {
            return Math.abs(origin) <= halfExtent ? new double[]{tMin, tMax} : null;
        }

        double a = (-halfExtent - origin) / direction;
        double b = (halfExtent - origin) / direction;
        if (a > b) {
            double swap = a;
            a = b;
            b = swap;
        }

        double min = Math.max(tMin, a);
        double max = Math.min(tMax, b);
        return min <= max ? new double[]{min, max} : null;
    }
}

