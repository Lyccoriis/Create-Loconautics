package com.lycoris.loconautics.trainphysics.drive;

import com.lycoris.loconautics.trainphysics.PoseTweak;
import com.lycoris.loconautics.trainphysics.SableTrain;
import com.lycoris.loconautics.trainphysics.SableTrain.Car;
import com.lycoris.loconautics.trainphysics.glue.RailSnap;
import com.lycoris.loconautics.trainphysics.rail.RailCarriage;
import com.lycoris.loconautics.trainphysics.spawn.SubLevelSplitWatcher;
import com.lycoris.loconautics.trainphysics.visual.BogeyYawVisual;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3f;
import org.joml.Vector3d;



public final class BogeyVisualUpdates {
    private BogeyVisualUpdates() {
    }

    static void updateYaw(SableTrain train) {
        com.lycoris.loconautics.trainphysics.wheels.GhostWheelBodies.syncSwing(train);
        if (train.isDerailed()) {
            clearBogeyRailPose(train);
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(train.level());
        if (container == null) {
            return;
        }
        Car car = train.car();
        if (car.subLevelId() == null
                || !(container.getSubLevel(car.subLevelId()) instanceof ServerSubLevel sub)
                || sub.isRemoved()) {
            return;
        }
        if (car.bogeys().isEmpty()) {
            return;
        }
        ServerLevel subLevel = sub.getLevel();
        for (SableTrain.Bogey bogey : car.bogeys()) {
            BlockPos worldPos = sub.getPlot().getCenterBlock().offset(bogey.localPos());
            if (subLevel.getBlockEntity(worldPos) instanceof AbstractBogeyBlockEntity be) {
                org.joml.Vector3f offsetLocal = new org.joml.Vector3f();
                org.joml.Vector3d counter = PoseTweak.counter(sub,
                        new org.joml.Vector3d(worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5),
                        car.carriage().referenceForward(), train.poseTweak());
                addSeatOffset(sub, car, bogey.rail(), worldPos, counter);
                BogeyYawVisual.setRailPose(be, railLinkOf(bogey.rail()), offsetLocal,
                        new Vector3f((float) counter.x, (float) counter.y, (float) counter.z));
            }
        }
        for (BlockPos local : com.lycoris.loconautics.trainphysics.spawn.SubLevelSplitWatcher.idleBogeysCached(train)) {
            BlockPos worldPos = sub.getPlot().getCenterBlock().offset(local);
            RailCarriage idleRail = com.lycoris.loconautics.trainphysics.spawn.SubLevelSplitWatcher
                    .idleBogeyRail(train, sub, local);
            if (idleRail != null && subLevel.getBlockEntity(worldPos) instanceof AbstractBogeyBlockEntity be) {
                org.joml.Vector3d counter = PoseTweak.counter(sub,
                        new org.joml.Vector3d(worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5),
                        car.carriage().referenceForward(), train.poseTweak());
                addSeatOffset(sub, car, idleRail, worldPos, counter);
                BogeyYawVisual.setRailPose(be, railLinkOf(idleRail),
                        new org.joml.Vector3f(),
                        new Vector3f((float) counter.x, (float) counter.y, (float) counter.z));
            }
        }
    }

    // The body glues to the chord between its bogeys, so on a vertical curve (the bottom and top of an incline) the
    // bogey blocks render off the rail even though the wheels are held on it. Publish the seat offset with the rail
    // pose so the client slides each bogey model from its block position back onto the rail under it.
    static Vector3d railSeatOffset(ServerSubLevel sub, Car car, RailCarriage rail, BlockPos blockPos) {
        if (rail == null) {
            return null;
        }
        Vector3d curve = rail.center();
        Vec3 up = rail.upNormal();
        if (curve == null || up == null) {
            return null;
        }
        Vector3d anchor = RailSnap.glueAnchorLocal(sub, car);
        Vector3d blockMid = TrainRailAdvance.bogeyAnchorLocal(sub, car);
        if (anchor == null || blockMid == null) {
            return null;
        }
        // block center height above the curve, a constant of the plot the train was assembled in
        double h = blockMid.y - anchor.y;
        if (Math.abs(h) > 4.0) {
            return null;
        }
        Vector3d target = new Vector3d(curve).fma(h, new Vector3d(up.x, up.y, up.z));
        Pose3d pose = sub.logicalPose();
        Vector3d blockCenter = pose.transformPosition(
                new Vector3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5), new Vector3d());
        Vector3d delta = target.sub(blockCenter);
        if (!isFinite(delta) || delta.lengthSquared() > 4.0) {
            return null;
        }
        return new Quaterniond(pose.orientation()).conjugate().transform(delta, new Vector3d());
    }

    private static void addSeatOffset(ServerSubLevel sub, Car car, RailCarriage rail, BlockPos blockPos,
                                      Vector3d counter) {
        Vector3d seat = railSeatOffset(sub, car, rail, blockPos);
        if (seat != null) {
            counter.add(seat);
        }
    }

    private static boolean isFinite(Vector3d v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    static BogeyYawVisual.Link railLinkOf(RailCarriage rail) {
        if (rail == null || rail.graph() == null) {
            return null;
        }
        TravellingPoint lead = rail.leading();
        TravellingPoint trail = rail.trailing();
        if (lead == null || trail == null || lead.node1 == null || lead.node2 == null
                || trail.node1 == null || trail.node2 == null) {
            return null;
        }
        return new BogeyYawVisual.Link(rail.graph().id,
                lead.node1.getNetId(), lead.node2.getNetId(), (float) lead.position,
                trail.node1.getNetId(), trail.node2.getNetId(), (float) trail.position,
                rail.lastTravelSign() < 0.0);
    }

    static void clearBogeyRailPose(SableTrain train) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(train.level());
        if (container == null) {
            return;
        }
        Car car = train.car();
        if (car.subLevelId() == null
                || !(container.getSubLevel(car.subLevelId()) instanceof ServerSubLevel sub) || sub.isRemoved()) {
            return;
        }
        ServerLevel subLevel = sub.getLevel();
        for (SableTrain.Bogey bogey : car.bogeys()) {
            BlockPos worldPos = sub.getPlot().getCenterBlock().offset(bogey.localPos());
            if (subLevel.getBlockEntity(worldPos) instanceof AbstractBogeyBlockEntity be) {
                BogeyYawVisual.clearRailBodyPose(be);
            }
        }
    }

    static void updateWheels(SableTrain train) {
        Car car = train.car();
        if (SableTrainDriver.useForceGlue(car) || train.isTrackless()) return;
        double ds = train.speed();
        if (ds == 0.0) return;

        ServerSubLevelContainer container = SubLevelContainer.getContainer(train.level());
        if (container == null) return;
        if (car.subLevelId() == null
                || !(container.getSubLevel(car.subLevelId()) instanceof ServerSubLevel sub)
                || sub.isRemoved()) return;

        TrainRailAdvance.animateBogeyWheels(car, sub, ds);
    }
}
