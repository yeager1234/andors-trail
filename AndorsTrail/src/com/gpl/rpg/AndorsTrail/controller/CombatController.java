package com.gpl.rpg.AndorsTrail.controller;

import java.util.ArrayList;

import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.view.View;

import com.gpl.rpg.AndorsTrail.Dialogs;
import com.gpl.rpg.AndorsTrail.EffectCollection;
import com.gpl.rpg.AndorsTrail.R;
import com.gpl.rpg.AndorsTrail.context.ViewContext;
import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.AttackResult;
import com.gpl.rpg.AndorsTrail.model.ModelContainer;
import com.gpl.rpg.AndorsTrail.model.actor.Actor;
import com.gpl.rpg.AndorsTrail.model.actor.ActorTraits;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.model.actor.MonsterType;
import com.gpl.rpg.AndorsTrail.model.item.Loot;
import com.gpl.rpg.AndorsTrail.model.map.LayeredWorldMap;
import com.gpl.rpg.AndorsTrail.model.map.MonsterSpawnArea;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.view.MainView;

public final class CombatController {
	private final ViewContext context;
    private final WorldContext world;
    private final ModelContainer model;
    
	private Monster currentActiveMonster = null;
    private final ArrayList<Monster> killedMonsters = new ArrayList<Monster>();
    
	public CombatController(ViewContext context) {
    	this.context = context;
    	this.world = context;
    	this.model = world.model;
    }

	public static final int BEGIN_TURN_PLAYER = 0;
	public static final int BEGIN_TURN_MONSTERS = 1;
	public static final int BEGIN_TURN_CONTINUE = 2;
	
	public void enterCombat(int beginTurnAs) {
    	context.mainActivity.combatview.setVisibility(View.VISIBLE);
    	context.mainActivity.combatview.bringToFront();
    	model.uiSelections.isInCombat = true;
    	killedMonsters.clear();
    	context.mainActivity.clearMessages();
    	if (beginTurnAs == BEGIN_TURN_PLAYER) newPlayerTurn();
    	else if (beginTurnAs == BEGIN_TURN_MONSTERS) endPlayerTurn();
    	else maybeAutoEndTurn();
    	updateTurnInfo();
    }
    public void exitCombat(boolean displayLootDialog) {
    	setCombatSelection(null, null);
		context.mainActivity.combatview.setVisibility(View.GONE);
		model.uiSelections.isInCombat = false;
    	context.mainActivity.clearMessages();
    	currentActiveMonster = null;
    	if (!killedMonsters.isEmpty()) {
    		lootMonsters(killedMonsters, displayLootDialog);
    		killedMonsters.clear();
    	}
    	context.controller.queueAnotherTick();
    }
    
    private void lootMonsters(ArrayList<Monster> killedMonsters, boolean displayLootDialog) {
    	Loot loot = model.currentMap.getBagOrCreateAt(killedMonsters.get(0).position);
    	for(Monster m : killedMonsters) {
    		m.createLoot(loot);
    		model.statistics.addMonsterKill(m.monsterType);
    	}
    	if (loot.isEmpty()) return;
    	if (displayLootDialog) Dialogs.showMonsterLoot(context.mainActivity, context, loot);
    	ItemController.consumeNonItemLoot(loot, model);
    	context.mainActivity.statusview.update();
	}

	public boolean isMonsterTurn() { 
		return currentActiveMonster != null;
	}

	public void setCombatSelection(Monster selectedMonster) {
		Coord p = selectedMonster.rectPosition.findPositionAdjacentTo(model.player.position);
		setCombatSelection(selectedMonster, p);
	}
	public void setCombatSelection(Monster selectedMonster, Coord selectedPosition) {
		if (selectedMonster != null) {
			if (!selectedMonster.isAgressive()) return;
		}
		Coord previousSelection = model.uiSelections.selectedPosition;
		if (model.uiSelections.selectedPosition != null) {
			model.uiSelections.selectedPosition = null;
			context.mainActivity.redrawTile(previousSelection, MainView.REDRAW_TILE_SELECTION_REMOVED);
		}
		context.mainActivity.combatview.updateCombatSelection(selectedMonster, selectedPosition);
		model.uiSelections.selectedMonster = selectedMonster;
		if (selectedPosition != null) {
			model.uiSelections.selectedPosition = new Coord(selectedPosition);
			model.uiSelections.isInCombat = true;
			context.mainActivity.redrawTile(selectedPosition, MainView.REDRAW_TILE_SELECTION_ADDED);
		} else {
			model.uiSelections.selectedPosition = null;
		}
	}
	public void setCombatSelection(Coord p) {
		LayeredWorldMap map = model.currentMap;
		Monster m = map.getMonsterAt(p);
		if (m != null) {
			setCombatSelection(m, p);
		} else if (map.isWalkable(p)) {
			setCombatSelection(null, p);
		}
	}

	private void message(String s) {
		context.mainActivity.message(s);
	}
	private boolean useAPs(int cost) {
		if (model.player.useAPs(cost)) {
			context.mainActivity.combatview.updatePlayerAP(model.player.ap);
			return true;
		} else {
			message(context.mainActivity.getResources().getString(R.string.combat_not_enough_ap));
			return false;
		}
	}
	
	public boolean canExitCombat() { return getAdjacentMonster() == null; }
	private Monster getAdjacentMonster() {
		for (MonsterSpawnArea a : model.currentMap.spawnAreas) {
			for (Monster m : a.monsters) {
				if (!m.isAgressive()) continue;
				if (m.rectPosition.isAdjacentTo(model.player.position)) {
					return m;
				}
			}
		}
		return null;
	}

	public void executeMoveAttack(int dx, int dy) {
		if (isMonsterTurn()) {
			forceFinishMonsterAction();
		} else if (world.model.uiSelections.selectedMonster != null) {
			executeAttack();
		} else if (world.model.uiSelections.selectedPosition != null) {
			executeCombatMove(world.model.uiSelections.selectedPosition);
		} else if (canExitCombat()) {
			exitCombat(true);
		} else if (dx != 0 || dy != 0) {
			executeFlee(dx, dy);
		}
	}
	
	private void executeFlee(int dx, int dy) {
		if (!context.movementController.findWalkablePosition(dx, dy)) return;
		Monster m = model.currentMap.getMonsterAt(model.player.nextPosition);
		if (m != null) return;
		executeCombatMove(world.model.player.nextPosition);
	}
	
	private void executeAttack() {
		context.effectController.waitForCurrentEffect();
		
		if (!useAPs(model.player.traits.attackCost)) return;
		Monster target = model.uiSelections.selectedMonster;
			
		AttackResult attack = playerAttacks(model, target);
		Resources r = context.mainActivity.getResources();
		if (attack.isHit) {
			String msg;
			
			final String monsterName = target.traits.name;
			if (attack.isCriticalHit) {
				msg = r.getString(R.string.combat_result_herohitcritical, monsterName, attack.damage);
			} else {
				msg = r.getString(R.string.combat_result_herohit, monsterName, attack.damage);
			}
			if (attack.targetDied) {
				msg += " " + r.getString(R.string.combat_result_herokillsmonster, monsterName, attack.damage);
			}
			message(msg);
			startAttackEffect(attack, model.uiSelections.selectedPosition);
			if (!attack.targetDied) {
				context.mainActivity.combatview.updateMonsterHealth(target.health);
			} else {
				killedMonsters.add(target);
				Monster nextMonster = getAdjacentMonster();
				if (nextMonster == null) {
					exitCombat(true);
					return;
				} else {
					setCombatSelection(nextMonster);
				}
			}
		} else {
			message(r.getString(R.string.combat_result_heromiss));
		}
		
		maybeAutoEndTurn();
	}

	private void maybeAutoEndTurn() {
		if (model.player.ap.current < model.player.useItemCost
			&& model.player.ap.current < model.player.traits.attackCost
			&& model.player.ap.current < model.player.traits.moveCost) {
			endPlayerTurn();
		}
	}

	private void executeCombatMove(final Coord dest) {
		if (model.uiSelections.selectedMonster != null) return;
		if (dest == null) return;
		if (!useAPs(model.player.traits.moveCost)) return;

		if (Constants.roll100(Constants.FLEE_FAIL_CHANCE_PERCENT)) {
			fleeingFailed();
			return;
		}
		
		model.player.nextPosition.set(dest);
		context.movementController.moveToNextIfPossible(false);
		
		if (canExitCombat()) exitCombat(true);
		else maybeAutoEndTurn();
	}

	private void fleeingFailed() {
		Resources r = context.mainActivity.getResources();
		message(r.getString(R.string.combat_flee_failed));
		endPlayerTurn();
	}

	private final Handler monsterTurnHandler = new Handler() {
        public void handleMessage(Message msg) {
        	monsterTurnHandler.removeMessages(0);
            CombatController.this.handleNextMonsterAction();
        }
	};
	public void endPlayerTurn() {
		model.player.ap.current = 0;
		for (MonsterSpawnArea a : model.currentMap.spawnAreas) {
			for (Monster m : a.monsters) {
				m.setMaxAP();
			}
		}
		handleNextMonsterAction();
	}

	private void forceFinishMonsterAction() {
		//TODO:
		return;
		//waitForEffect = false;
		//monsterTurnHandler.removeMessages(0);
		//monsterTurnHandler.sendEmptyMessage(0);
	}

	private Monster determineNextMonster(Monster previousMonster) {
		if (previousMonster != null) {
			if (previousMonster.useAPs(previousMonster.traits.attackCost)) return previousMonster;
		}
		
		for (MonsterSpawnArea a : model.currentMap.spawnAreas) {
			for (Monster m : a.monsters) {
				if (!m.isAgressive()) continue;
				
				if (m.rectPosition.isAdjacentTo(model.player.position)) {
					if (m.useAPs(m.traits.attackCost)) return m;
				}
			}
		}
		return null;
	}
	
	private void handleNextMonsterAction() {
		if (!context.model.uiSelections.isMainActivityVisible) return;
		
		context.effectController.waitForCurrentEffect();
		
		currentActiveMonster = determineNextMonster(currentActiveMonster);
		if (currentActiveMonster == null) {
			endMonsterTurn();
			return;
		}
		context.mainActivity.combatview.updateTurnInfo(currentActiveMonster);
		Resources r = context.mainActivity.getResources();
		AttackResult attack = monsterAttacks(model, currentActiveMonster);
		String monsterName = currentActiveMonster.traits.name;
		if (attack.isHit) {
			startAttackEffect(attack, model.player.position);
			if (attack.isCriticalHit) {
				message(r.getString(R.string.combat_result_monsterhitcritical, monsterName, attack.damage));
			} else {
				message(r.getString(R.string.combat_result_monsterhit, monsterName, attack.damage));
			}
			if (attack.targetDied) {
				exitCombat(false);
				context.controller.handlePlayerDeath();
				return;
			}
		} else {
			message(r.getString(R.string.combat_result_monstermiss, monsterName));
		}
		context.mainActivity.statusview.update();
		monsterTurnHandler.sendEmptyMessageDelayed(0, context.preferences.attackspeed_milliseconds);
	}

	private void startAttackEffect(AttackResult attack, final Coord position) {
		if (context.preferences.attackspeed_milliseconds <= 0) return;
		context.effectController.startEffect(
				context.mainActivity.mainview
				, position
				, EffectCollection.EFFECT_BLOOD
				, attack.damage);
	}
	private void endMonsterTurn() {
		currentActiveMonster = null;
		newPlayerTurn();
	}
	
	private void newPlayerTurn() {
		model.player.setMaxAP();
    	updateTurnInfo();
	}
	private void updateTurnInfo() {
		context.mainActivity.combatview.updateTurnInfo(currentActiveMonster);
    	context.mainActivity.combatview.updatePlayerAP(model.player.ap);
	}
	
	private static float getAverageDamagePerHit(ActorTraits attacker, ActorTraits target) {
		float result = (float) (getAttackHitChance(attacker, target)) * attacker.damagePotential.average() / 100;
		result += (float) attacker.criticalChance * result * attacker.criticalMultiplier / 100;
		result -= target.damageResistance;
		return result;
	}
	private static float getAverageDamagePerTurn(ActorTraits attacker, ActorTraits target) {
		return getAverageDamagePerHit(attacker, target) * attacker.getAttacksPerTurn();
	}
	private static int getTurnsToKillTarget(ActorTraits attacker, ActorTraits target) {
		if (attacker.hasCriticalEffect()) {
			if (attacker.damagePotential.max * attacker.criticalMultiplier <= target.damageResistance) return 999;
		} else {
			if (attacker.damagePotential.max <= target.damageResistance) return 999;
		}
		
		float averageDamagePerTurn = getAverageDamagePerTurn(attacker, target);
		if (averageDamagePerTurn <= 0) return 100;
		return (int) Math.ceil(target.maxHP / averageDamagePerTurn);
	}
	public static int getMonsterDifficulty(WorldContext world, MonsterType monsterType) {
		// returns [0..100) . 100 == easy.
		int turnsToKillMonster = getTurnsToKillTarget(world.model.player.traits, monsterType);
		if (turnsToKillMonster >= 999) return 0;
		int turnsToKillPlayer = getTurnsToKillTarget(monsterType, world.model.player.traits);
		int result = 50 + (turnsToKillPlayer - turnsToKillMonster) * 2;
		if (result <= 1) return 1;
		else if (result > 100) return 100;
		return result;
	}
	
	public AttackResult playerAttacks(ModelContainer model, Monster currentMonster) {
    	AttackResult result = attack(model.player, currentMonster);
    	
    	if (result.targetDied) {
    		model.currentMap.remove(currentMonster);
    		context.mainActivity.redrawAll(MainView.REDRAW_ALL_MONSTER_KILLED);
		}

		return result;
	}
	
	public AttackResult monsterAttacks(ModelContainer model, Monster currentMonster) {
		return attack(currentMonster, model.player);
	}
	

	private static final int n = 50;
	private static final int F = 40;
	private static final float two_divided_by_PI = (float) (2f / Math.PI);
	private static int getAttackHitChance(final ActorTraits attacker, final ActorTraits target) {
		final int c = attacker.attackChance - target.blockChance;
		// (2/pi)*atan(..) will vary from -1 to +1 .
		return (int) (50 * (1 + two_divided_by_PI * (float)Math.atan((float)(c-n) / F)));
	}
	
	private static AttackResult attack(final Actor attacker, final Actor target) {
		int hitChance = getAttackHitChance(attacker.traits, target.traits);
		if (!Constants.roll100(hitChance)) return AttackResult.MISS;
		
		int damage = Constants.rollValue(attacker.traits.damagePotential);
		boolean isCriticalHit = false;
		if (attacker.traits.hasCriticalEffect()) {
			isCriticalHit = Constants.roll100(attacker.traits.criticalChance);
			if (isCriticalHit) {
				damage *= attacker.traits.criticalMultiplier;
			}
		}
		damage -= target.traits.damageResistance;
		if (damage < 0) damage = 0;
		target.health.subtract(damage, false);
			
		return new AttackResult(true, isCriticalHit, damage, target.health.current <= 0);
	}

	public void monsterSteppedOnPlayer(Monster m) {
		setCombatSelection(m);
		enterCombat(BEGIN_TURN_MONSTERS);
	}
	
	public void startFlee() {
		setCombatSelection(null, null);
		Resources r = context.mainActivity.getResources();
		message(r.getString(R.string.combat_begin_flee));
	}
}