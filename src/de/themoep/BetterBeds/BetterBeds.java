package de.themoep.BetterBeds;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class BetterBeds extends JavaPlugin implements Listener {
	
	private int minPlayers = 2;
	private double sleepPercentage = 0.5;
	private int nightSpeed = 0;
	private HashMap<UUID,HashSet<UUID>> asleepPlayers = new HashMap<UUID,HashSet<UUID>>();
	private String ghostMessage;
	private String leaveMessage;
	private String sleepMessage;
	private String wakeMessage;
	private String notifyMessage;
	private String notifyOnSingleMessage;
	private int transitionTask = 0;
	private HashMap<UUID,String> nameOfLastPlayerToEnterBed = new HashMap<UUID,String>(); 
	
	public void onEnable() {
		this.saveDefaultConfig();
		this.loadConfig();

		this.getServer().getPluginManager().registerEvents(this, this);
	}

	/**
	 * Reload command's method
	 */
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) throws NumberFormatException {
		if(cmd.getName().equalsIgnoreCase("betterbedsreload") || cmd.getName().equalsIgnoreCase("bbreload")) {
			sender.sendMessage("[BetterBeds] Reloading Config");
			this.loadConfig();
			return true;
		}
		return false;		
	}
	
	/**
	 * Loads the options from the config file into the plugins variables
	 */
	private void loadConfig() {
		this.reloadConfig();
		this.minPlayers = this.getConfig().getInt("minPlayers");
		try {
			this.sleepPercentage = Double.parseDouble(this.getConfig().getString("sleepPercentage").replaceAll(" ", "").replace("%", ""));
		} catch (NumberFormatException e) {
			this.getLogger().log(Level.WARNING,"You have an Error in your config at the sleepPercentage-node! Using the default now: " + this.sleepPercentage);
		} 
		if (this.sleepPercentage > 1) {
			this.sleepPercentage = this.sleepPercentage / 100;
		}
		this.nightSpeed = this.getConfig().getInt("nightSpeed", 300);
		this.ghostMessage = this.getConfig().getString("msg.ghost", "You may not rest now, there are ghosts nearby");
		this.sleepMessage = this.getConfig().getString("msg.sleep", "Player {player} is now sleeping. {sleeping}/{online} ({percentage}%) {more} more required!");
		this.leaveMessage = this.getConfig().getString("msg.leave", "Player {player} is no longer sleeping. {sleeping}/{online} ({percentage}%)");
		this.wakeMessage = this.getConfig().getString("msg.wake", "Wakey, wakey, rise and shine...Good Morning everyone!");
		this.notifyMessage = this.getConfig().getString("msg.notify", "{sleeping} players have gone to bed. Skipping the night!");
		this.notifyOnSingleMessage = this.getConfig().getString("msg.notifyOnSingle", "{player} has gone to bed. Skipping the night!");
	}
	
	/**
	 * Calculate if number of sleeping players is enough to fast forward the night. 
	 * TODO: The calculation should really be its own method because we don't yet check if there are enough players sleeping when some players quit the game/change the world.
	 * @param event PlayerBedEnterEvent
	 */
	@EventHandler
	public void onPlayerBedEnter(PlayerBedEnterEvent event) {
		if(event.isCancelled() 
				|| event.getPlayer().hasPermission("betterbeds.ignore") 
				|| !event.getPlayer().hasPermission("betterbeds.sleep")
				|| this.transitionTask != 0)
			return;

		World world = event.getBed().getWorld();
		int calculatedPlayers = 0;
		for(Player p: world.getPlayers()) {
			if(p != event.getPlayer() && p.hasPermission("betterbeds.ghost") && !p.isSleeping()) {
				event.setCancelled(true);
				event.getPlayer().sendMessage(this.ghostMessage);
				this.getLogger().info("There is a ghost online, players can't sleep now!");
				return;
			}
			if(!p.hasPermission("betterbeds.ignore"))
				calculatedPlayers++;
		}
		
		HashSet<UUID> playerList = new HashSet<UUID>();
		
		if(this.asleepPlayers.containsKey(world.getUID()))
			playerList = this.asleepPlayers.get(world.getUID());
		
		playerList.add(event.getPlayer().getUniqueId());
		
		this.asleepPlayers.put(world.getUID(), playerList);
		nameOfLastPlayerToEnterBed.put(world.getUID(), event.getPlayer().getName());
		
		this.getLogger().log(Level.INFO, event.getPlayer().getName() + " sleeps now. " + playerList.size() + "/" + calculatedPlayers + " players are asleep in world " + world.getName());

		if(!this.checkPlayers(world, false)) {
			String msg = this.buildMsg(this.sleepMessage, event.getPlayer().getName(), playerList.size(), calculatedPlayers);
			for (UUID playerid : playerList)
				if (this.getServer().getPlayer(playerid) != null && this.getServer().getPlayer(playerid).isOnline())
					this.getServer().getPlayer(playerid).sendMessage(ChatColor.GOLD + msg);
		}
	}

	/**
	 * Check if enough players are asleep.
	 * @param world The world to calculate with
	 * @param playerQuit
	 */
	public boolean isPlayerLimitSatisfied(World world, boolean playerQuit) {
		if(!this.asleepPlayers.containsKey(world.getUID()))
			return false;

		int calculatedPlayers = (playerQuit) ? -1 : 0;
		for(Player p: world.getPlayers()) {
			if(p.hasPermission("betterbeds.ghost") && !p.isSleeping())
				return false;
			if(p.hasPermission("betterbeds.sleep") && !p.hasPermission("betterbeds.ignore"))
				calculatedPlayers++;
		}		
		HashSet<UUID> playerList = this.asleepPlayers.get(world.getUID());
		return (playerList.size() >= minPlayers	&& playerList.size() >= calculatedPlayers * this.sleepPercentage) 
				|| (playerList.size() < minPlayers && playerList.size() >= calculatedPlayers);
	}
	
	/**
	 * Check if enough players are asleep and fast forward if so.
	 * @param world The world to calculate with
	 * @param playerQuit
	 */
	private boolean checkPlayers(final World world, boolean playerQuit) {
		if (isPlayerLimitSatisfied(world, playerQuit)) {
			if (this.nightSpeed == 0) {
				this.getLogger().log(Level.INFO, "Set time to dawn in world " + world.getName());
				notifyPlayers(world);
				setWorldToMorning(world);
			} else {
				if (transitionTask != 0)
					return false;
				
				notifyPlayers(world);

				this.getLogger().log(Level.INFO, "Timelapsing " + nightSpeed + "x until dawn in world " + world.getName());
				transitionTask = this.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
					public void run() {
						if (!isPlayerLimitSatisfied(world, false)) {
							getServer().getScheduler().cancelTask(transitionTask);
							transitionTask = 0;
							return;
						}
						long currentTime = world.getTime();
						long newTime = currentTime + nightSpeed; 
						if (newTime >= 23450) {
							getServer().getScheduler().cancelTask(transitionTask);
							transitionTask = 0;
							setWorldToMorning(world);
						} else {
							world.setTime(currentTime + nightSpeed);
						}
					}
				}, 1L, 1L);
			}
			return true;
		}
		return false;
	}

	/**
	 * Notifies all the players within a world of skipping the night
	 * @param world
	 */
	private void notifyPlayers(World world) {
		HashSet<UUID> playerList = this.asleepPlayers.get(world.getUID());
		String msg = buildMsg((playerList.size() > 1) ? this.notifyMessage : this.notifyOnSingleMessage,
				nameOfLastPlayerToEnterBed.get(world.getUID()),
				playerList.size(), 
				countQualifyingPlayers(world));
		for (Player p : world.getPlayers()) {
			p.sendMessage(ChatColor.GOLD + msg);
		}
	}

	/**
	 * Resets the world's climate and the list of sleeping players.
	 * @param world
	 */
	public void setWorldToMorning(World world)
	{
		world.setTime(23450);
		if(world.hasStorm())
			world.setStorm(false);
		
		if(world.isThundering())
			world.setThundering(false);

		HashSet<UUID> playerList = this.asleepPlayers.get(world.getUID());
		for(UUID playerid : playerList) {
			if(this.getServer().getPlayer(playerid) != null && this.getServer().getPlayer(playerid).isOnline()) {
				this.getServer().getPlayer(playerid).sendMessage(ChatColor.GOLD + this.wakeMessage);
			}
		}
		
		playerList.clear();
	}
	
	/**
	 * Recalculates the number of sleeping players if a player leaves his bed
	 * @param event PlayerBedLeaveEvent
	 */
	@EventHandler
	public void onPlayerBedLeave(PlayerBedLeaveEvent event) {
		this.calculateBedleave(event.getPlayer(), event.getBed().getWorld());
	}	

	/**
	 * Recalculates the number of sleeping players if a player quits the game between 12500 and 100 time ticks
	 * @param event PlayerQuitEvent
	 */
	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		this.calculateBedleave(event.getPlayer(), event.getPlayer().getWorld());
		this.checkPlayers(event.getPlayer().getWorld(), true);
	}
	
	/**
	 * Recalculates the number of sleeping players if a player changes from a normal world between 12500 and 100 time ticks
	 * @param event PlayerChangedWorldEvent
	 */
	@EventHandler
	public void onWorldChange(PlayerChangedWorldEvent event) {
		this.calculateBedleave(event.getPlayer(), event.getFrom());
		this.checkPlayers(event.getFrom(), false);
	}

	/**
	 * Calculates what happens when a player leaves the bed. 
	 * 
	 * @param player The player who left the bed
	 * @param world The world the bed was in (because it's possible the player isn't there anymore when he existed it)
	 */
	private void calculateBedleave(Player player, World world) {

		if(world.getEnvironment() == Environment.NORMAL && world.getTime() >= 12500 && world.getTime() <= 100)
			return;
		if(!this.asleepPlayers.containsKey(world.getUID()))
			return;
		
		if(this.asleepPlayers.get(world.getUID()).contains(player.getUniqueId())) {
			int calculatedPlayers = countQualifyingPlayers(world);

			this.asleepPlayers.get(world.getUID()).remove(player.getUniqueId());
	
			HashSet<UUID> playerList = this.asleepPlayers.get(world.getUID());
	
			this.getLogger().log(Level.INFO, player.getName() + " is not sleeping anymore. " + playerList.size() + "/" + calculatedPlayers + " players are asleep in world " + world.getName());
			
			String msg =  this.buildMsg(this.leaveMessage,player.getName(),playerList.size(),calculatedPlayers);			
			
			for(UUID playerid : this.asleepPlayers.get(world.getUID()))
				if(this.getServer().getPlayer(playerid) != null && this.getServer().getPlayer(playerid).isOnline()) 
					this.getServer().getPlayer(playerid).sendMessage(ChatColor.GOLD + msg);

			this.checkPlayers(world, false);
		}
	}
	
	private int countQualifyingPlayers(World world) {
		int calculatedPlayers = 0;
		for(Player p: world.getPlayers())
			if(!p.hasPermission("betterbeds.ignore") && p.hasPermission("betterbeds.sleep"))
				calculatedPlayers ++;
		return calculatedPlayers;
	}

	/**
	 * Converts eventual parameters in a message into its real values.
	 * TODO: Make it so that not every parameter is required!
	 * @param msg String of the message to convert 
	 * @param playername String of the playername to insert in the message
	 * @param sleeping Integer of sleeping players
	 * @param online Integer of online players in world
	 * @return String of the converted message
	 */
	private String buildMsg(String msg, String playername, int sleeping, int online) {
		if (playername != null)
			msg = msg.replace("{player}", ChatColor.RED + playername + ChatColor.GOLD);
		msg = msg.replace("{sleeping}", sleeping + "");
		msg = msg.replace("{online}", online + "");
		float percentage = (float) Math.round(((double) sleeping / online * 100 * 100) / 100 );
		msg = msg.replace("{percentage}", String.format("%.2f", percentage));
		int more = (int) (Math.ceil(online * this.sleepPercentage) - sleeping);
		msg = msg.replace("{more}", Integer.toString(more));
		return msg;
	}
}
