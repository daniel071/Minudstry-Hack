package mindustry.entities.type;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Rect;
import arc.util.ArcAnnotate.Nullable;
import arc.util.Interval;
import arc.util.Time;
import mindustry.Vars;
import mindustry.annotations.Annotations.Loc;
import mindustry.annotations.Annotations.Remote;
import mindustry.content.StatusEffects;
import mindustry.ctype.ContentType;
import mindustry.entities.EntityGroup;
import mindustry.entities.Units;
import mindustry.entities.traits.ShooterTrait;
import mindustry.entities.traits.SolidTrait;
import mindustry.entities.traits.TargetTrait;
import mindustry.entities.units.StateMachine;
import mindustry.entities.units.UnitCommand;
import mindustry.entities.units.UnitDrops;
import mindustry.entities.units.UnitState;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.type.StatusEffect;
import mindustry.type.TypeID;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.ui.Cicon;
import mindustry.world.Tile;
import mindustry.world.blocks.BuildBlock;
import mindustry.world.blocks.defense.DeflectorWall.DeflectorEntity;
import mindustry.world.blocks.units.CommandCenter.CommandCenterEntity;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryEntity;
import mindustry.world.meta.BlockFlag;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import static mindustry.Vars.*;

/** Base class for AI units. */
public abstract class BaseUnit extends Unit implements ShooterTrait{
    protected static int timerIndex = 0;

    protected static final int timerTarget = timerIndex++;
    protected static final int timerTarget2 = timerIndex++;
    protected static final int timerShootLeft = timerIndex++;
    protected static final int timerShootRight = timerIndex++;

    protected boolean loaded;
    protected UnitType type;
    protected Interval timer = new Interval(5);
    protected StateMachine state = new StateMachine();
    protected TargetTrait target;

    protected int spawner = noSpawner;

    /** internal constructor used for deserialization, DO NOT USE */
    public BaseUnit(){
    }

    @Remote(called = Loc.server)
    public static void onUnitDeath(BaseUnit unit){
        if(unit == null) return;

        if(net.server() || !net.active()){
            UnitDrops.dropItems(unit);
        }

        unit.onSuperDeath();
        unit.type.deathSound.at(unit);

        //visual only.
        if(net.client()){
            Tile tile = world.tile(unit.spawner);
            if(tile != null){
                tile.block().unitRemoved(tile, unit);
            }

            unit.spawner = noSpawner;
        }

        //must run afterwards so the unit's group is not null when sending the removal packet
        Core.app.post(unit::remove);
    }

    @Override
    public float drag(){
        return type.drag;
    }

    @Override
    public TypeID getTypeID(){
        return type.typeID;
    }

    @Override
    public void onHit(SolidTrait entity){
        if(entity instanceof Bullet && ((Bullet)entity).getOwner() instanceof DeflectorEntity && player != null && getTeam() != player.getTeam()){
            Core.app.post(() -> {
                if(isDead()){
                    Events.fire(Trigger.phaseDeflectHit);
                }
            });
        }
    }

    public @Nullable
    Tile getSpawner(){
        return world.tile(spawner);
    }

    public boolean isCommanded(){
        return indexer.getAllied(team, BlockFlag.comandCenter).size != 0 && indexer.getAllied(team, BlockFlag.comandCenter).first().entity instanceof CommandCenterEntity;
    }

    public @Nullable UnitCommand getCommand(){
        if(isCommanded()){
            return indexer.getAllied(team, BlockFlag.comandCenter).first().<CommandCenterEntity>ent().command;
        }
        return null;
    }

    /**Called when a command is recieved from the command center.*/
    public void onCommand(UnitCommand command){

    }

    /** Initialize the type and team of this unit. Only call once! */
    public void init(UnitType type, Team team){
        if(this.type != null) throw new RuntimeException("This unit is already initialized!");

        this.type = type;
        this.team = team;
    }

    /** @return whether this unit counts toward the enemy amount in the wave UI. */
    public boolean countsAsEnemy(){
        return true;
    }

    public UnitType getType(){
        return type;
    }

    public void setSpawner(Tile tile){
        this.spawner = tile.pos();
    }

    public void rotate(float angle){
        rotation = Mathf.slerpDelta(rotation, angle, type.rotatespeed);
    }

    public boolean targetHasFlag(BlockFlag flag){
        return (target instanceof TileEntity && ((TileEntity)target).tile.block().flags.contains(flag)) ||
        (target instanceof Tile && ((Tile)target).block().flags.contains(flag));
    }

    public void setState(UnitState state){
        this.state.set(state);
    }

    public boolean retarget(){
        return timer.get(timerTarget, 20);
    }

    /** Only runs when the unit has a target. */
    public void behavior(){

    }

    public void updateTargeting(){
        if(target == null || (target instanceof Unit && (target.isDead() || target.getTeam() == team))
        || (target instanceof TileEntity && ((TileEntity)target).tile.entity == null)){
            target = null;
        }
    }

    public void targetClosestAllyFlag(BlockFlag flag){
        Tile target = Geometry.findClosest(x, y, indexer.getAllied(team, flag));
        if(target != null) this.target = target.entity;
    }

    public void targetClosestEnemyFlag(BlockFlag flag){
        Tile target = Geometry.findClosest(x, y, indexer.getEnemy(team, flag));
        if(target != null) this.target = target.entity;
    }

    public void targetClosest(){
        TargetTrait newTarget = Units.closestTarget(team, x, y, Math.max(getWeapon().bullet.range(), type.range), u -> type.targetAir || !u.isFlying());
        if(newTarget != null){
            target = newTarget;
        }
    }

    public @Nullable Tile getClosest(BlockFlag flag){
        return Geometry.findClosest(x, y, indexer.getAllied(team, flag));
    }

    public @Nullable Tile getClosestSpawner(){
        return Geometry.findClosest(x, y, Vars.spawner.getGroundSpawns());
    }

    public @Nullable TileEntity getClosestEnemyCore(){
        return Vars.state.teams.closestEnemyCore(x, y, team);
    }

    public UnitState getStartState(){
        return null;
    }

    public boolean isBoss(){
        return hasEffect(StatusEffects.boss);
    }

    @Override
    public float getDamageMultipler(){
        return status.getDamageMultiplier() * Vars.state.rules.unitDamageMultiplier;
    }

    @Override
    public boolean isImmune(StatusEffect effect){
        return type.immunities.contains(effect);
    }

    @Override
    public boolean isValid(){
        return super.isValid() && isAdded();
    }

    @Override
    public Interval getTimer(){
        return timer;
    }

    @Override
    public int getShootTimer(boolean left){
        return left ? timerShootLeft : timerShootRight;
    }

    @Override
    public Weapon getWeapon(){
        return type.weapon;
    }

    @Override
    public TextureRegion getIconRegion(){
        return type.icon(Cicon.full);
    }

    @Override
    public int getItemCapacity(){
        return type.itemCapacity;
    }

    @Override
    public void interpolate(){
        super.interpolate();

        if(interpolator.values.length > 0){
            rotation = interpolator.values[0];
        }
    }

    @Override
    public float maxHealth(){
        return type.health * Vars.state.rules.unitHealthMultiplier;
    }

    @Override
    public float mass(){
        return type.mass;
    }

    @Override
    public boolean isFlying(){
        return type.flying;
    }

    @Override
    public void update(){
        if(isDead()){
            //dead enemies should get immediately removed
            remove();
            return;
        }

        hitTime -= Time.delta();

        if(net.client()){
            interpolate();
            status.update(this);
            return;
        }

        if(!isFlying() && (world.tileWorld(x, y) != null && !(world.tileWorld(x, y).block() instanceof BuildBlock) && world.tileWorld(x, y).solid())){
            kill();
        }

        avoidOthers();

        if(spawner != noSpawner && (world.tile(spawner) == null || !(world.tile(spawner).entity instanceof UnitFactoryEntity))){
            kill();
        }

        updateTargeting();

        state.update();
        updateVelocityStatus();

        if(target != null) behavior();

        if(!isFlying()){
            clampPosition();
        }
    }

    @Override
    public void draw(){

    }

    @Override
    public float maxVelocity(){
        return type.maxVelocity;
    }

    @Override
    public void removed(){
        super.removed();
        Tile tile = world.tile(spawner);
        if(tile != null && !net.client()){
            tile.block().unitRemoved(tile, this);
        }

        spawner = noSpawner;
    }

    @Override
    public float drawSize(){
        return type.hitsize * 10;
    }

    @Override
    public void onDeath(){
        Call.onUnitDeath(this);
    }

    @Override
    public void added(){
        state.set(getStartState());

        if(!loaded){
            health(maxHealth());
        }

        if(isCommanded()){
            onCommand(getCommand());
        }
    }

    @Override
    public void hitbox(Rect rect){
        rect.setSize(type.hitsize).setCenter(x, y);
    }

    @Override
    public void hitboxTile(Rect rect){
        rect.setSize(type.hitsizeTile).setCenter(x, y);
    }

    @Override
    public EntityGroup targetGroup(){
        return unitGroup;
    }

    @Override
    public byte version(){
        return 0;
    }

    @Override
    public void writeSave(DataOutput stream) throws IOException{
        super.writeSave(stream);
        stream.writeByte(type.id);
        stream.writeInt(spawner);
    }

    @Override
    public void readSave(DataInput stream, byte version) throws IOException{
        super.readSave(stream, version);
        loaded = true;
        byte type = stream.readByte();
        this.spawner = stream.readInt();

        this.type = content.getByID(ContentType.unit, type);
        add();
    }

    @Override
    public void write(DataOutput data) throws IOException{
        super.writeSave(data);
        data.writeByte(type.id);
        data.writeInt(spawner);
    }

    @Override
    public void read(DataInput data) throws IOException{
        float lastx = x, lasty = y, lastrot = rotation;

        super.readSave(data, version());

        this.type = content.getByID(ContentType.unit, data.readByte());
        this.spawner = data.readInt();

        interpolator.read(lastx, lasty, x, y, rotation);
        rotation = lastrot;
        x = lastx;
        y = lasty;
    }

    public void onSuperDeath(){
        super.onDeath();
    }
}
