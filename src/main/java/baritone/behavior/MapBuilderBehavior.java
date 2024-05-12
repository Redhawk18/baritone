/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.api.behavior.IMapBuilderBehavior;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.input.Input;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.Baritone;
import baritone.utils.accessor.ILevel;
import baritone.utils.schematic.format.defaults.LitematicaSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public class MapBuilderBehavior extends Behavior implements IMapBuilderBehavior {
    public MapBuilderBehavior(Baritone baritone) {
        super(baritone);
    }

    public static final int hotBarSize = 9;
    public static final int inventorySize = 27;
    public static final int totalInventorySize = hotBarSize + inventorySize;

    // Baritone
    private boolean paused = true;
    private int timer = 0;
    private State currentState = State.Nothing;

    // Schematic
    private ISchematic schematic;
    private String schematicName;
    private Vec3i schematicOrigin;

    // Shulker variables
    private List<ShulkerInfo> shulkerList = new ArrayList<>();
    private BetterBlockPos curCheckingShulker = null;

    // Locations
    private BetterBlockPos cachedPlayerFeet = null;
    private BetterBlockPos pathBackLoc = null;

    // Material or Block variables
    private List<BlockState> allBlocks = new LinkedList<>();
    private boolean cursorStackNonEmpty = false;

    private BlockState closestNeededBlock;
    private int stacksToLoot = 0;

    // we will revisit a 2nd block resource grab later
    // private BlockState mostCommonBlock; //holds the most common block in the schematic
    // private int amountOfMostCommonBlock = -1;



    private enum State {
        Nothing,

        Building,

        ShulkerSearchPathing,
        ShulkerSearchOpening,
        ShulkerSearchChecking,

        SchematicScanning,
        PathingToShulker,
        OpeningShulker,
        LootingShulker,

        PathingBack
    }

    private static class ShulkerInfo {
        public ShulkerInfo(BetterBlockPos Pos) {
            pos = Pos;
        }
        public BetterBlockPos pos;
        public List<ItemStack> contents = new ArrayList<>();
        public boolean checked = false;
    }

    @Override
    public void build() {
        if (!LitematicaHelper.isLitematicaPresent()) {
            Helper.HELPER.logDirect("Litematica is not present");
            return;
        }

        if (!LitematicaHelper.hasLoadedSchematic()) {
            Helper.HELPER.logDirect("No schematic currently open");
            return;
        }

        int litematicaIndex = 0;
        schematicName = LitematicaHelper.getName(litematicaIndex);
        schematicOrigin = LitematicaHelper.getOrigin(litematicaIndex);
        try {
            LitematicaSchematic schematic = new LitematicaSchematic(NbtIo.readCompressed(Files.newInputStream(LitematicaHelper.getSchematicFile(litematicaIndex).toPath())), false);
            this.schematic = LitematicaHelper.blackMagicFuckery(schematic, litematicaIndex);
        } catch (Exception e) {
            Helper.HELPER.logDirect("Failure to load schematic");
            return;
        }

        shulkerList = new ArrayList<>();
        timer = 0;
        curCheckingShulker = null;
        allBlocks = new LinkedList<>();
        closestNeededBlock = null;
        cachedPlayerFeet = null;
        currentState = State.Nothing;
        pathBackLoc = null;

        populateShulkerInfoList();
        paused = false;
    }

    @Override
    public void stop() {
        paused = true;
        currentState = State.Nothing;
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getPathingBehavior().cancelEverything();
        Helper.HELPER.logDirect("Stopped");
    }

    @Override
    public void printStatus() {
        Helper.HELPER.logDirect("State:" + currentState);
        Helper.HELPER.logDirect("Paused: " + paused);
        Helper.HELPER.logDirect("Timer: " + timer);
    }

    private void startBuild() {
        Baritone.settings().buildRepeat.value = new Vec3i(0, 0, 0);

        baritone.getPathingBehavior().cancelEverything();
        baritone.getBuilderProcess().build(schematicName, schematic, schematicOrigin);
        currentState = State.Building;
    }

    @Override
    public void onTick(TickEvent event) {
        // Helper.HELPER.logDirect("onTick Start");
        if (paused || schematic == null || ctx.player() == null || ctx.player().getInventory().isEmpty()) {
            return;
        }

        timer++;
        final int timerMax = Baritone.settings().buildTimer.value;

        if (!ctx.player().containerMenu.getCarried().isEmpty() && ctx.player().containerMenu == ctx.player().inventoryMenu) {
            if (cursorStackNonEmpty && timer >= 80) {
                // We have some item on our cursor for 80 ticks, try to place it somewhere
                timer = 0;


                int emptySlot = getItemSlot(Item.getId(Items.AIR));
                if (emptySlot != -1) {
                    Helper.HELPER.logDirect("Had " + ctx.player().containerMenu.getCarried().getDisplayName() + " on our cursor. Trying to place into slot " + emptySlot);

                    if (emptySlot <= 8) {
                        // Fix slot id if it's a hotbar slot
                        emptySlot += totalInventorySize;
                    }
                    ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, emptySlot, 0, ClickType.PICKUP, ctx.player());
                    // ctx.player()Controller.updateController();
                    cursorStackNonEmpty = false;
                    return;
                }
            } else if (!cursorStackNonEmpty) {
                cursorStackNonEmpty = true;
                timer = 0;
                return;
            }
            return;
        } else {
            cursorStackNonEmpty = false;
        }

        switch (currentState) {
            case Nothing: {
                for (ShulkerInfo curShulker : shulkerList) {
                    if (!curShulker.checked) {
                        currentState = State.ShulkerSearchPathing;
                        timer = 0;
                        return;
                    }
                }
                startBuild();
                break;
            }

            case Building: {
                if (baritone.getBuilderProcess().isActive() && baritone.getBuilderProcess().isPaused()) {
                    timer = 0;
                    currentState = State.SchematicScanning;
                    return;
                }

                if (!baritone.getBuilderProcess().isActive() && timer >= 300) {
                    // Must have disconnected and reconnected, restart build
                    currentState = State.Nothing;
                    timer = 0;
                    return;
                }

                if (baritone.getBuilderProcess().isActive() && !baritone.getBuilderProcess().isPaused() && timer >= timerMax) {
                    if (ctx.player().hasContainerOpen()) {
                        ctx.player().closeContainer(); // Close chest gui so we can actually build
                        timer = 0;
                        return;
                    }

                    if (cachedPlayerFeet == null) {
                        cachedPlayerFeet = new BetterBlockPos(ctx.playerFeet());
                        timer = 0;
                        return;
                    }

                    if (cachedPlayerFeet.distanceTo(ctx.playerFeet()) < 5) {
                        Helper.HELPER.logDirect("We haven't moved in " + timerMax + " ticks. Restarting builder");
                        timer = 0;
                        pathBackLoc = findPathBackLoc(true);
                        Helper.HELPER.logDirect("Pathing back loc: " + pathBackLoc);
                        currentState = State.PathingBack;
                        baritone.getPathingBehavior().cancelEverything();

                        return;
                    }

                    if (!cachedPlayerFeet.equals(ctx.playerFeet())) {
                        cachedPlayerFeet = new BetterBlockPos(ctx.playerFeet());
                        timer = 0;
                        return;
                    }
                }
                break;
            }

            case ShulkerSearchPathing: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                curCheckingShulker = getShulkerToCheck();
                if (curCheckingShulker == null) {
                    currentState = State.Nothing;
                    return;
                }
                Helper.HELPER.logDirect("found current shulker: " + curCheckingShulker);

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, curCheckingShulker,
                        ctx.playerController().getBlockReachDistance());

                if (shulkerReachable.isPresent()) {
                    currentState = State.ShulkerSearchOpening;
                    timer = 0;
                } else {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(curCheckingShulker, 4));
                }
                break;
            }

            case ShulkerSearchOpening: {
                if (timer < 20) {
                    return;
                }

                if (curCheckingShulker == null) {
                    currentState = State.ShulkerSearchPathing;
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, curCheckingShulker,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                if (shulkerReachable.isEmpty()) {
                    currentState = State.ShulkerSearchPathing;
                    timer = 0;
                    return;
                }

                if (!ctx.isLookingAt(curCheckingShulker)) {
                    timer = 0;
                    return;
                }

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(ctx.player().hasContainerOpen())) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    timer = 0;
                } else {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    currentState = State.ShulkerSearchChecking;
                }
                break;
            }

            case ShulkerSearchChecking: {
                if (timer < 40) {
                    return;
                }

                if (!(ctx.player().hasContainerOpen())) {
                    currentState = State.ShulkerSearchOpening;
                    return;
                }

                for (ShulkerInfo shulkerInfo : shulkerList) {
                    if (shulkerInfo.pos.equals(curCheckingShulker)) {
                        shulkerInfo.checked = true;
                        shulkerInfo.contents = getOpenShulkerContents();
                        if (shulkerInfo.contents != null) {
                            for (ItemStack itemStack : shulkerInfo.contents) {
                                // Any random item would cause a cast failure that isn't a block
                                if (!(itemStack.getItem() instanceof BlockItem)) continue;
                                BlockState state = ((BlockItem) itemStack.getItem())
                                        .getBlock()
                                        .getStateForPlacement(
                                                new BlockPlaceContext(
                                                        new UseOnContext(
                                                                ctx.world(),
                                                                ctx.player(),
                                                                InteractionHand.MAIN_HAND,
                                                                itemStack,
                                                                new BlockHitResult(
                                                                        new Vec3(
                                                                                ctx.player().position().x,
                                                                                ctx.player().position().y,
                                                                                ctx.player().position().z),
                                                                        Direction.UP,
                                                                        ctx.playerFeet(),
                                                                        false)) {}));
                                if (!allBlocks.contains(state) && !(state.getBlock() instanceof AirBlock)) {
                                    allBlocks.add(state);
                                }
                            }
                        }
                        break;
                    }
                }

                currentState = State.ShulkerSearchPathing;
                ctx.player().closeContainer();
                timer = 0;
                break;
            }

            case SchematicScanning: {
                closestNeededBlock = findNeededBlockNew();

                if (closestNeededBlock == null || closestNeededBlock.getBlock() instanceof AirBlock) {
                    // We probably have everything we need, but baritone is just being retarded
                    // So we have to manually walk back to our building spot
                    Helper.HELPER.logDirect("Have what we need trying to force path back");
                    pathBackLoc = findPathBackLoc(false);
                    Helper.HELPER.logDirect("Pathing back loc: " + pathBackLoc);
                    currentState = State.PathingBack;
                    baritone.getPathingBehavior().cancelEverything();
                    return;
                }

                // Finds how many stacks of already chosen block are needed.
                int stacksNeeded = stacksOfBlockNeededSchematic(closestNeededBlock);
                int airSlots = getItemStackCountInventory(Blocks.AIR.defaultBlockState());
                if (isSingleBlockBuild()) {
                    stacksToLoot = Math.min(stacksNeeded, airSlots);
                } else {
                    // Carpet is easier to place, so we restock in bulk.
                    if (closestNeededBlock.getBlock() instanceof CarpetBlock) {
                        stacksToLoot = Math.min(stacksNeeded, Baritone.settings().stacksToLootCarpet.value);
                    } else {
                        stacksToLoot = Math.min(stacksNeeded, Baritone.settings().stacksToLoot.value);
                    }

                    if (airSlots < stacksToLoot) {
                        stacksToLoot = airSlots;
                    }
                }


                // Figures out which shulker contains the blocks needed.
                Helper.HELPER.logDirect("We need " + stacksToLoot + " stacks of: " + closestNeededBlock.toString());
                curCheckingShulker = null;
                for (ShulkerInfo curShulker : shulkerList) {
                    for (ItemStack stack : curShulker.contents) {
                        if (!(stack.getItem() instanceof BlockItem)) continue;
                        //Gets the block state since you can't use block ids due to blocks having same ids but are different
                        BlockState state = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(
                                new BlockPlaceContext(
                                        new UseOnContext(
                                                ctx.world(),
                                                ctx.player(),
                                                InteractionHand.MAIN_HAND,
                                                stack,
                                                new BlockHitResult(
                                                        new Vec3(
                                                                ctx.player().position().x,
                                                                ctx.player().position().y,
                                                                ctx.player().position().z),
                                                        Direction.UP,
                                                        ctx.playerFeet(),
                                                        false)) {}
                                ));

                        // Torches can be placed in diff facing directions, so we need this
                        if (closestNeededBlock.getBlock() instanceof TorchBlock) {
                            if (((BlockItem) stack.getItem()).getBlock().equals(closestNeededBlock.getBlock())) {
                                curCheckingShulker = curShulker.pos;
                            }
                        } else {
                            if (state.equals(closestNeededBlock)) {
                                curCheckingShulker = curShulker.pos;
                            }
                        }
                    }
                }
                if (curCheckingShulker == null) {
                    Helper.HELPER.logDirect("Shulkers don't have any " + closestNeededBlock);
                    Helper.HELPER.logDirect("Please refill and restart building");
                    paused = true;
                    return;
                }
                currentState = State.PathingToShulker;
                break;
            }

            case PathingToShulker: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (curCheckingShulker == null) {
                    currentState = State.Nothing;
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, curCheckingShulker,
                        ctx.playerController().getBlockReachDistance());

                if (shulkerReachable.isPresent()) {
                    currentState = State.OpeningShulker;
                } else {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(curCheckingShulker, 4));
                }
                break;
            }

            case OpeningShulker: {
                if (timer < 10) {
                    return;
                }

                if (curCheckingShulker == null) {
                    currentState = State.PathingToShulker;
                    baritone.getInputOverrideHandler().clearAllKeys();
                    return;
                }

                Optional<Rotation> shulkerReachable = RotationUtils.reachable(ctx, curCheckingShulker,
                        ctx.playerController().getBlockReachDistance());
                shulkerReachable.ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));

                if (shulkerReachable.isEmpty()) {
                    currentState = State.PathingToShulker;
                    return;
                }

                baritone.getInputOverrideHandler().clearAllKeys();
                if (!(ctx.player().hasContainerOpen())) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    timer = 0;
                } else {
                    currentState = State.LootingShulker;
                }
                break;
            }

            case LootingShulker: {
                if (timer < 40) {
                    return;
                }

                if (!(ctx.player().hasContainerOpen())) {
                    currentState = State.OpeningShulker;
                    return;
                }

                if (getItemStackCountInventory(closestNeededBlock) >= stacksToLoot) {
                    // Have what we need
                    timer = 0;
                    ctx.player().closeContainer();
                    currentState = State.Nothing;
                    return;
                }

                if (getChestSlotCount(closestNeededBlock) == 0) {
                    // Shulker doesn't have what we need, did we accidentally open the wrong one?
                    timer = 0;
                    ctx.player().closeContainer();
                    currentState = State.PathingToShulker;
                    return;
                }



                // Loot shulker and update its contents
                BlockState itemLooted = lootItemChestSlot(closestNeededBlock);
                for (ShulkerInfo curShulker : shulkerList) {
                    if (curShulker.pos.equals(curCheckingShulker)) {
                        curShulker.contents = getOpenShulkerContents(); // Update the shulker contents
                        if (curShulker.contents == null || curShulker.contents.isEmpty()) {
                            Helper.HELPER.logDirect("Shulker no longer has items. Finishing looting early");
                            ctx.player().closeContainer();
                            currentState = State.Nothing;
                        }
                        timer = 0;
                        return;
                    }
                }

                break;
            }

            case PathingBack: {
                if (baritone.getCustomGoalProcess().isActive()) {
                    return; // Wait to get there
                }

                if (pathBackLoc == null) {
                    currentState = State.Nothing;
                    return;
                }

                if (ctx.playerFeet().distanceTo(pathBackLoc) < 3) {
                    // We have arrived
                    currentState = State.Nothing;
                } else {
                    // Keep trying to get there
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pathBackLoc.getX(), pathBackLoc.getY(), pathBackLoc.getZ()));
                }
                break;
            }
        }
    }

    private boolean isSingleBlockBuild() {
        BlockState firstState = null;
        for (ShulkerInfo curShulker : shulkerList) {
            for (ItemStack stack : curShulker.contents) {
                if (!(stack.getItem() instanceof BlockItem)) continue;
                BlockState state = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}));
                if (firstState == null) {
                    firstState = state;
                } else if (!firstState.equals(state)) {
                    return false;
                }

            }
        }
        return true;
    }

    private BetterBlockPos findPathBackLoc(boolean findFurthest) {
        // List<BlockPos> set = findInvalidBlocks(); // test bartione's version since we dont have to maintain that and it likely has less bugs
        HashSet<BetterBlockPos> set = baritone.getBuilderProcess().getIncorrectPositions();
        List<BlockPos> validPathBacks = new LinkedList<>();
        Helper.HELPER.logDirect("Found " + set.size() + " invalid locations.");
        for (BlockPos pos : set) {
            // If the invalid block we are checking isn't loaded, or we don't have any of it in our inventory, just skip
            if (!ctx.world().isLoaded(pos) ||
                    ctx.world().getBlockState(pos).getBlock() instanceof AirBlock ||
                    getItemStackCountInventory(ctx.world().getBlockState(pos)) == 0) {
                continue;
            }
            List<BlockPos> validSideBlocks = new LinkedList<>();
            for (int x = -4; x < 4; x++) {
                for (int z = -4; z < 4; z++) {
                    BlockPos curBlockPos = pos.offset(x, 0, z);
                    if (ctx.world().isLoaded(curBlockPos)) { // check if its in render distance, not if its in cache
                        Block sideBlock = ctx.world().getBlockState(curBlockPos).getBlock();
                        // Make sure side block isn't air, water, or web
                        if (!(sideBlock instanceof AirBlock) && !(sideBlock instanceof LiquidBlock) && !(sideBlock instanceof WebBlock)) {
                            // We can stand here
                            if (sideBlock instanceof CarpetBlock || sideBlock instanceof PressurePlateBlock) {
                                validSideBlocks.add(curBlockPos);
                            } else {
                                validSideBlocks.add(curBlockPos.above()); // Add one height so we can stand
                            }
                        }
                    }
                }
            }
            BlockPos closestSideBlock = null;
            double closestDistSideBlock = Double.MAX_VALUE;
            for (BlockPos curPos : validSideBlocks) {
                double tempDist = pos.distToCenterSqr(curPos.getX(), curPos.getY(), curPos.getZ());
                if (tempDist < closestDistSideBlock) {
                    closestSideBlock = curPos;
                    closestDistSideBlock = tempDist;
                }
            }
            if (closestSideBlock != null) {
                validPathBacks.add(closestSideBlock);
            }
        }

        if (findFurthest) {
            BlockPos furthestPos = null;
            double furthestDist = 0;
            for (BlockPos curPos : validPathBacks) {
                double tempDist = ctx.playerFeet().distToCenterSqr(curPos.getX(), curPos.getY(), curPos.getZ());
                if (tempDist > furthestDist) {
                    furthestPos = curPos;
                    furthestDist = tempDist;
                }
            }
            return furthestPos != null ? new BetterBlockPos(furthestPos) : null;
        }

        BlockPos closestPos = null;
        double closestDist = Double.MAX_VALUE;
        for (BlockPos curPos : validPathBacks) {
            double tempDist = ctx.playerFeet().distToCenterSqr(curPos.getX(), curPos.getY(), curPos.getZ());
            if (tempDist < closestDist) {
                closestPos = curPos;
                closestDist = tempDist;
            }
        }
        return closestPos != null ? new BetterBlockPos(closestPos) : null;
    }

    private List<BlockPos> findInvalidBlocks() {
        List<BlockPos> invalidPos = new LinkedList<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + schematicOrigin.getX();
                    int blockY = y + schematicOrigin.getY();
                    int blockZ = z + schematicOrigin.getZ();
                    BlockPos curBlockPos = new BlockPos(blockX, blockY, blockZ);
                    BlockState current = ctx.world().getBlockState(curBlockPos);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (ctx.world().isLoaded(curBlockPos)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (!current.equals(schematic.desiredState(x, y, z, current, this.allBlocks))) {
                            invalidPos.add(curBlockPos);
                        }
                    }
                }
            }
        }
        return invalidPos;
    }

    private int getChestSlotCount(BlockState item) {
        int count = 0;
        AbstractContainerMenu curContainer = ctx.player().containerMenu;
        for (int i = 0; i < inventorySize; i++) {
            if (!(curContainer.getSlot(i).getItem().getItem() instanceof BlockItem)) {
                continue;
            }
            BlockState state = ((BlockItem) curContainer.getSlot(i).getItem().getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, curContainer.getSlot(i).getItem(), new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}));
            if (state.equals(item)) {
                count++;
            }
        }
        return count;
    }

    // Returns the item in our inventory before looting,
    // So if we loot into air slot, then it's an Air item
    // Otherwise it's the item that got swapped into the chest
    private BlockState lootItemChestSlot(BlockState itemLoot) {
        AbstractContainerMenu curContainer = ctx.player().containerMenu;
        for (int i = 0; i < inventorySize; i++) { //loops through all slots in shulker box
            //checks to see if there are problem items within shulker
            if (curContainer.getSlot(i).getItem().getItem() instanceof AirItem || //empty slots
                    !(curContainer.getSlot(i).getItem().getItem() instanceof BlockItem) ||
                    curContainer.getSlot(i).getItem().getCount() < Baritone.settings().minimumItemsInAStack.value) { //non-blocks
                continue;
            }

            BlockState swappedItem = Blocks.AIR.defaultBlockState();
            BlockState state =
                    ((BlockItem) curContainer.getSlot(i).getItem().getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, curContainer.getSlot(i).getItem(), new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}));

            if (state.equals(itemLoot)) {
                int swapSlot = getRandomBlockIdSlot();

                if (getItemStackCountInventory(itemLoot) == 0 && getItemSlot(Item.getId(Items.AIR)) == -1) {
                    // We have no needed items and no air slots, so we have to swap
                    if (swapSlot == 8) {
                        swapSlot = getRandomBlockIdSlotNoHotbar();
                        if (swapSlot == -1) {
                            return Blocks.AIR.defaultBlockState();
                        }
                    }
                    swappedItem = ((BlockItem) ctx.player().getInventory().getItem(swapSlot).getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, ctx.player().getInventory().getItem(swapSlot), new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}));
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, ctx.player()); // Pickup from chest
                    ctx.playerController().windowClick(curContainer.containerId, swapSlot < 9 ? swapSlot + 54 : swapSlot + 18, 0, ClickType.PICKUP, ctx.player()); // Have to convert slot id to single chest slot id
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.PICKUP, ctx.player()); // Place back into chest
                } else {
                    // Item exist already or there's an air slot, so we can just do a quick move
                    ctx.playerController().windowClick(curContainer.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                }

                // ctx.player()Controller.updateController();
                return swappedItem;
            } //watch out if state is still null and not assigned a new value
        }

        return Blocks.AIR.defaultBlockState();
    }

    private int getRandomBlockIdSlotNoHotbar() {
        for (int i = totalInventorySize - 1; i >= hotBarSize; i--) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem) {
                return i;
            }
        }
        return -1;
    }

    private int getRandomBlockIdSlot() {
        for (int i = 0; i < totalInventorySize; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem) {
                return i;
            }
        }
        return -1;
    }

    private int getItemSlot(int itemId) {
        for (int i = 0; i < totalInventorySize; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (Item.getId(stack.getItem()) == itemId) {
                return i;
            }
        }

        return -1;
    }

    /// Finds the block giving us the biggest problem.
    private BlockState findNeededBlockNew() {
        List<Tuple<BetterBlockPos, BlockState>> blocksNeeded = new LinkedList<>();
        HashSet<BetterBlockPos> set = baritone.getBuilderProcess().getIncorrectPositions();
        for (BetterBlockPos pos : set) {
            BlockState current = ctx.world().getBlockState(pos);
            if (!schematic.inSchematic(
                    pos.x - schematicOrigin.getX(),
                    pos.y - schematicOrigin.getY(),
                    pos.z - schematicOrigin.getZ(),
                    current)) {
                continue;
            }
            //Item blockNeeded = Item.getItemFromBlock(schematic.desiredState(pos.x, pos.y, pos.z, current, this.allBlocks).getBlock());
            BlockState desiredState = schematic.desiredState(
                    pos.x - schematicOrigin.getX(),
                    pos.y - schematicOrigin.getY(),
                    pos.z - schematicOrigin.getZ(),
                    current, this.allBlocks);
            if (getItemStackCountInventory(desiredState) == 0) {
                blocksNeeded.add(new Tuple<>(pos, desiredState));
            }
        }

        BlockState closestItem = null;
        double closestDistance = Double.MAX_VALUE;
        for (Tuple<BetterBlockPos, BlockState> curCheck : blocksNeeded) {
            double tempDistance = baritone.getPlayerContext().playerFeet().distanceTo(curCheck.getA());
            if (tempDistance < closestDistance) {
                closestDistance = tempDistance;
                closestItem = curCheck.getB();
            }
        }

        if (closestItem != null) {
            return closestItem;
        }

        return findNeededClosestBlock();
    }

    private BlockState findNeededClosestBlock() {
        List<Tuple<BlockPos, BlockState>> blocksNeeded = new LinkedList<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + schematicOrigin.getX();
                    int blockY = y + schematicOrigin.getY();
                    int blockZ = z + schematicOrigin.getZ();
                    BlockPos curBlockPos = new BlockPos(blockX, blockY, blockZ);
                    BlockState current = ctx.world().getBlockState(curBlockPos);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (ctx.world().isLoaded(curBlockPos)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (!current.equals(schematic.desiredState(x, y, z, current, this.allBlocks))) {
                            if (getItemStackCountInventory(schematic.desiredState(x, y, z, current, this.allBlocks)) == 0) {
                                // We don't have any of that block, see if we can even place there
                                boolean canPlace = false;
                                List<BlockPos> sideBlocks = new LinkedList<>();
                                sideBlocks.add(curBlockPos.north());
                                sideBlocks.add(curBlockPos.east());
                                sideBlocks.add(curBlockPos.south());
                                sideBlocks.add(curBlockPos.west());
                                sideBlocks.add(curBlockPos.below());
                                for (BlockPos curPos : sideBlocks) {
                                    if (!(ctx.world().getBlockState(curPos).getBlock() instanceof AirBlock) && !(ctx.world().getBlockState(curPos).getBlock() instanceof LiquidBlock)) {
                                        canPlace = true;
                                    }
                                }
                                if (canPlace) {
                                    blocksNeeded.add(new Tuple<>(curBlockPos, schematic.desiredState(x, y, z, current, this.allBlocks)));
                                }

                            }
                        }
                    }
                }
            }
        }

        BlockState closestItem = Blocks.AIR.defaultBlockState();
        double closestDistance = Double.MAX_VALUE;
        for (Tuple<BlockPos, BlockState> curCheck : blocksNeeded) {
            double tempDistance = baritone.getPlayerContext().playerFeet().distToCenterSqr(curCheck.getA().getX(), curCheck.getA().getY(), curCheck.getA().getZ());
            if (tempDistance < closestDistance) {
                closestDistance = tempDistance;
                closestItem = curCheck.getB();
            }
        }

        return closestItem;
    }

    private int stacksOfBlockNeededSchematic(BlockState neededBlock) {
        int count = 0;
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + schematicOrigin.getX();
                    int blockY = y + schematicOrigin.getY();
                    int blockZ = z + schematicOrigin.getZ();
                    BlockPos curBlockPos = new BlockPos(blockX, blockY, blockZ);
                    BlockState current = ctx.world().getBlockState(curBlockPos);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (ctx.world().isLoaded(curBlockPos)) { // check if its in render distance, not if its in cache
                        BlockState desiredState = schematic.desiredState(x, y, z, current, this.allBlocks);
                        if (!current.equals(desiredState)) {
                            // Block isn't in its correct state, so we need some
                            if (desiredState.equals(neededBlock)) {
                                // Found the type we were searching for
                                count++;
                            }
                        }
                    }
                }
            }
        }

        return (int) Math.ceil(count / 64.0);
    }

    private int getItemStackCountInventory(BlockState item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem) {
                BlockState state = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(
                        new BlockPlaceContext(
                                new UseOnContext(
                                        ctx.world(),
                                        ctx.player(),
                                        InteractionHand.MAIN_HAND,
                                        stack,
                                        new BlockHitResult(
                                                new Vec3(
                                                        ctx.player().position().x,
                                                        ctx.player().position().y,
                                                        ctx.player().position().z),
                                                Direction.UP,
                                                ctx.playerFeet(),
                                                false)) {}));
                if (state.equals(item)) {
                    count++;
                }
            } else if (stack.isEmpty() && item.getBlock() instanceof AirBlock) {
                // Counting air slots
                count++;
            }
        }

        return count;
    }

//    private BlockState findMostCommonBlock() {
//        //use a TreeMap to find what block in the schematic is the most common
//        TreeMap<Integer, BlockState> blocksInSchematic = new TreeMap<>();
//
//        //populate hashmap with blocks from schematic
//        //TODO add this
//
//        //somehow get the highest key value from the treemap
//        //return that highest key as its BlockState
//        amountOfMostCommonBlock = blocksInSchematic.lastKey(); //amount of common block
//        return (BlockState) blocksInSchematic.lastEntry(); //the block itself
//    }

    //shulker methods
    private List<ItemStack> getOpenShulkerContents() {
        if (!(ctx.player().hasContainerOpen())) {
            return null;
        }

        List<ItemStack> shulkerContents = new ArrayList<>();
        AbstractContainerMenu curContainer = ctx.player().containerMenu;
        for (int i = 0; i < inventorySize; i++) {
            if (!(curContainer.getSlot(i).getItem().getItem() instanceof AirItem)) {
                //int itemId = Item.getId(curContainer.getSlot(i).getItem().getItem());
                shulkerContents.add(curContainer.getSlot(i).getItem());
            }
        }

        return shulkerContents;
    }

    private BetterBlockPos getShulkerToCheck() {
        BetterBlockPos closestPos = null;
        double closestDist = Double.MAX_VALUE;
        for (ShulkerInfo shulkerInfo : shulkerList) {
            if (!shulkerInfo.checked) {
                double dist = shulkerInfo.pos.distanceTo(ctx.playerFeet());
                if (dist < closestDist) {
                    closestDist = dist;
                    closestPos = shulkerInfo.pos;
                }
            }
        }
        return closestPos;
    }

    private void populateShulkerInfoList() {
        shulkerList.clear();
        List<BetterBlockPos> shulkerBoxes = findShulkerBoxes();
        for (BetterBlockPos pos : shulkerBoxes) {
            shulkerList.add(new ShulkerInfo(pos));
        }
    }

    private List<BetterBlockPos> findShulkerBoxes() {
        List<BetterBlockPos> foundBoxes = new LinkedList<>();

        for (TickingBlockEntity tickingBlockEntity : ((ILevel) ctx.world()).getBlockEntityTickers()) {
            BlockEntity blockEntity = ctx.world().getBlockEntity(tickingBlockEntity.getPos());
            if (blockEntity instanceof ShulkerBoxBlockEntity) {
                foundBoxes.add(new BetterBlockPos(blockEntity.getBlockPos()));
            }
        }

        return foundBoxes;
    }
}