package com.stuffaboutcode.canaryraspberryjuice;

import java.io.*;
import java.net.*;
import java.util.*;

import net.canarymod.api.*;
import net.canarymod.api.world.*;
import net.canarymod.api.world.position.*;
import net.canarymod.api.world.blocks.*;
import net.canarymod.api.entity.living.humanoid.Player;
import net.canarymod.hook.player.BlockRightClickHook;
import net.canarymod.hook.player.ChatHook;

// Remote session class manages commands
public class RemoteSession {

	// origin is the spawn location on the world
	private Location origin;

	private Socket socket;

	private BufferedReader in;

	private BufferedWriter out;
	
	private Thread inThread;
	
	private Thread outThread;

	private ArrayDeque<String> inQueue = new ArrayDeque<String>();

	private ArrayDeque<String> outQueue = new ArrayDeque<String>();

	public boolean running = true;

	public boolean pendingRemoval = false;

	public CanaryRaspberryJuicePlugin plugin;

	protected ArrayDeque<BlockRightClickHook> blockHitQueue = new ArrayDeque<BlockRightClickHook>();
	
	protected ArrayDeque<ChatHook> chatPostedQueue = new ArrayDeque<ChatHook>();

	private int maxCommandsPerTick = 9000;

	private boolean closed = false;

	private Player attachedPlayer = null;
	
	private World currentWorld = null;
	
	private Server currentServer = null;

	public RemoteSession(CanaryRaspberryJuicePlugin plugin, Socket socket) throws IOException {
		this.socket = socket;
		this.plugin = plugin;
		init();
	}

	public void init() throws IOException {
		socket.setTcpNoDelay(true);
		socket.setKeepAlive(true);
		socket.setTrafficClass(0x10);
		this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
		startThreads();
		plugin.getLogman().info("Opened connection to" + socket.getRemoteSocketAddress() + ".");
	}

	protected void startThreads() {
		inThread = new Thread(new InputThread());
		inThread.start();
		outThread = new Thread(new OutputThread());
		outThread.start();
	}

	public Location getOrigin() {
		return origin;
	}

	public void setOrigin(Location origin) {
		this.origin = origin;
	}

	public Socket getSocket() {
		return socket;
	}
	
	public Server getServer() {
		if(currentServer == null) currentServer = plugin.getServer();
		return currentServer;
	}
	
	public World getWorld() {
		if(currentWorld == null) currentWorld = plugin.getWorld();
		return currentWorld;
	}

	/** called from the server main thread */
	public void tick() {
		
		if (origin == null) this.origin = plugin.getSpawnLocation();
		int processedCount = 0;
		String message;
		while ((message = inQueue.poll()) != null) {
			handleLine(message);
			processedCount++;
			if (processedCount >= maxCommandsPerTick) {
				plugin.getLogman().warn("Over " + maxCommandsPerTick +
					" commands were queued - deferring " + inQueue.size() + " to next tick");
				break;
			}
		}

		if (!running && inQueue.size() <= 0) {
			pendingRemoval = true;
		}
	}

	protected void handleLine(String line) {
		//System.out.println(line);
		String methodName = line.substring(0, line.indexOf("("));
		//split string into args, handles , inside " i.e. ","
		String[] args = line.substring(line.indexOf("(") + 1, line.length() - 1).split(",");
		//System.out.println(methodName + ":" + Arrays.toString(args));
		handleCommand(methodName, args);
	}

	protected void handleCommand(String c, String[] args) {
		
		try {
			// get the server
			Server server = getServer();
			
			// get the world
			World world = getWorld();		
			
			// world.getBlock
			if (c.equals("world.getBlock")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				send(world.getBlockAt(loc).getTypeId());
			
			// world.getBlocks
			} else if (c.equals("world.getBlocks")) {
				Location loc1 = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Location loc2 = parseRelativeBlockLocation(args[3], args[4], args[5]);
				send(getBlocks(loc1, loc2));
				
			// world.getBlockWithData
			} else if (c.equals("world.getBlockWithData")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				send(world.getBlockAt(loc).getTypeId() + "," + world.getBlockAt(loc).getData());
				
			// world.setBlock
			} else if (c.equals("world.setBlock")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				updateBlock(world, loc, Short.parseShort(args[3]), args.length > 4? Short.parseShort(args[4]) : (short) 0);
				
			// world.setBlocks
			} else if (c.equals("world.setBlocks")) {
				Location loc1 = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Location loc2 = parseRelativeBlockLocation(args[3], args[4], args[5]);
				short blockType = Short.parseShort(args[6]);
				short data = args.length > 7? Short.parseShort(args[7]) : (short) 0;
				setCuboid(loc1, loc2, blockType, data);
				
			// world.getPlayerIds
			} else if (c.equals("world.getPlayerIds")) {
				StringBuilder bdr = new StringBuilder();
				for (Player p: server.getPlayerList()) {
					bdr.append(p.getID());
					bdr.append("|");
				}
				bdr.deleteCharAt(bdr.length()-1);
				send(bdr.toString());

			// world.getPlayerId
			} else if (c.equals("world.getPlayerId")) {
				Player p = plugin.getNamedPlayer(args[0]);
				if (p != null) {
					send(p.getID());
				} else {
					plugin.getLogman().info("Player [" + args[0] + "] not found.");
					send("Fail");
				}
				
			// chat.post
			} else if (c.equals("chat.post")) {
				//create chat message from args as it was split by ,
				String chatMessage = "";
				int count;
				for(count=0;count<args.length;count++){
					chatMessage = chatMessage + args[count] + ",";
				}
				chatMessage = chatMessage.substring(0, chatMessage.length() - 1);
				server.broadcastMessage(chatMessage);
			} else if (c.equals("chat.consoleCommand")) {
				//create chat message from args as it was split by ,
				String command = "";
				int count;
				for(count=0;count<args.length;count++){
					command = command + args[count] + ",";
				}
				command = command.substring(0, command.length() - 1);
				server.consoleCommand(command);
			
			// events.clear
			} else if (c.equals("events.clear")) {
				blockHitQueue.clear();
				chatPostedQueue.clear();
			
			// events.block.hits
			} else if (c.equals("events.block.hits")) {
				// this doesn't work with multiplayer! need to think about how this should work
				StringBuilder b = new StringBuilder();
		 		BlockRightClickHook event;
				while ((event = blockHitQueue.poll()) != null) {
					Block block = event.getBlockClicked();
					Location loc = block.getLocation();
					b.append(blockLocationToRelative(loc));
					b.append(",");
					b.append(blockFaceToNotch(block.getFaceClicked()));
					b.append(",");
					b.append(event.getPlayer().getID());
					if (blockHitQueue.size() > 0) {
						b.append("|");
					}
				}
				//DEBUG
				//plugin.getLogman().info(b.toString());
				send(b.toString());
				
			// events.chat.posts
			} else if (c.equals("events.chat.posts")) {
				StringBuilder b = new StringBuilder();
		 		ChatHook event;
				while ((event = chatPostedQueue.poll()) != null) {
					b.append(event.getPlayer().getID());
					b.append(",");
					b.append(event.getMessage());
					if (chatPostedQueue.size() > 0) {
						b.append("|");
					}
				}
				//DEBUG
				//plugin.getLogman().info(b.toString());
				send(b.toString());
				
			// player.getTile
			} else if (c.equals("player.getTile")) {
				String name = null;
				if (args.length > 0) {
					name = args[0];
				}
				Player currentPlayer = getCurrentPlayer(name);
				send(blockLocationToRelative(currentPlayer.getLocation()));
				
			// player.setTile
			} else if (c.equals("player.setTile")) {
				String name = null, x = args[0], y = args[1], z = args[2];
				if (args.length > 3) {
					name = args[0]; x = args[1]; y = args[2]; z = args[3];
				}
				Player currentPlayer = getCurrentPlayer(name);
				//get players current location, so when they are moved we will use the same pitch and yaw (rotation)
				Location loc = currentPlayer.getLocation();
				currentPlayer.teleportTo(parseRelativeBlockLocation(x, y, z, loc.getPitch(), loc.getRotation()));
				
			// player.getPos
			} else if (c.equals("player.getPos")) {
				String name = null;
				if (args.length > 0) {
					name = args[0];
				}
				Player currentPlayer = getCurrentPlayer(name);
				send(locationToRelative(currentPlayer.getLocation()));
				
			// player.setPos
			} else if (c.equals("player.setPos")) {
				String name = null, x = args[0], y = args[1], z = args[2];
				if (args.length > 3) {
					name = args[0]; x = args[1]; y = args[2]; z = args[3];
				}
				Player currentPlayer = getCurrentPlayer(name);
				//get players current location, so when they are moved we will use the same pitch and yaw (rotation)
				Location loc = currentPlayer.getLocation();
				currentPlayer.teleportTo(parseRelativeLocation(x, y, z, loc.getPitch(), loc.getRotation()));
				
			// player.getDirection
			} else if (c.equals("player.getDirection")) {
				String name = null;
				if (args.length > 0) {
					name = args[0];
				}
				Player currentPlayer = getCurrentPlayer(name);
				Vector3D direction = getDirection(currentPlayer);
				send(direction.getX() + "," + direction.getY() + "," + direction.getZ());

			// player.getRotation
			} else if (c.equals("player.getRotation")) {
				String name = null;
				if (args.length > 0) {
					name = args[0];
				}
				Player currentPlayer = getCurrentPlayer(name);
				send(currentPlayer.getLocation().getRotation());
				
			// player.getPitch
			} else if (c.equals("player.getPitch")) {
				String name = null;
				if (args.length > 0) {
					name = args[0];
				}
				Player currentPlayer = getCurrentPlayer(name);
				send(currentPlayer.getLocation().getPitch());

			// world.getHeight
			} else if (c.equals("world.getHeight")) {
				Location loc = parseRelativeBlockLocation(args[0], "0", args[1]);
				send(world.getHighestBlockAt(loc.getBlockX(), loc.getBlockZ()) - origin.getBlockY());
	    	
			// entity.getTile
			} else if (c.equals("entity.getTile")) {
				//get entity based on id
				//EntityLiving entity = plugin.getEntityLiving(Integer.parseInt(args[0]));
				Player entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					send(blockLocationToRelative(entity.getLocation()));
				} else {
					plugin.getLogman().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}
			// entity.setTile
			} else if (c.equals("entity.setTile")) {
				String x = args[1], y = args[2], z = args[3];
				//get entity based on id
				//EntityLiving entity = plugin.getEntityLiving(Integer.parseInt(args[0]));
				Player entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					//get entity's current location, so when they are moved we will use the same pitch and yaw (rotation)
					Location loc = entity.getLocation();
					entity.teleportTo(parseRelativeBlockLocation(x, y, z, loc.getPitch(), loc.getRotation()));
				} else {
					plugin.getLogman().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}
	        
			// entity.getPos
			} else if (c.equals("entity.getPos")) {
				//get entity based on id
				//EntityLiving entity = plugin.getEntityLiving(Integer.parseInt(args[0]));
				Player entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					send(locationToRelative(entity.getLocation()));
				} else {
					plugin.getLogman().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}
			
			// entity.setPos
			} else if (c.equals("entity.setPos")) {
				String x = args[1], y = args[2], z = args[3];
				//get entity based on id
				//EntityLiving entity = plugin.getEntityLiving(Integer.parseInt(args[0]));
				Player entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					//get entity's current location, so when they are moved we will use the same pitch and yaw (rotation)
					Location loc = entity.getLocation();
					entity.teleportTo(parseRelativeLocation(x, y, z, loc.getPitch(), loc.getRotation()));
				} else {
					plugin.getLogman().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}

			// entity.getDirection
			} else if (c.equals("entity.getDirection")) {
				//get entity based on id
				//EntityLiving entity = plugin.getEntityLiving(Integer.parseInt(args[0]));
				Player entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					Vector3D direction = getDirection(entity);
					send(direction.getX() + "," + direction.getY() + "," + direction.getZ());
				} else {
					plugin.getLogman().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}
				
			// entity.getRotation
			} else if (c.equals("entity.getRotation")) {
				//get entity based on id
				//EntityLiving entity = plugin.getEntityLiving(Integer.parseInt(args[0]));
				Player entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					send(entity.getLocation().getRotation());
				} else {
					plugin.getLogman().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}
				
			// entity.getPitch
			} else if (c.equals("entity.getPitch")) {
				//get entity based on id
				//EntityLiving entity = plugin.getEntityLiving(Integer.parseInt(args[0]));
				Player entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) {
					send(entity.getLocation().getPitch());
				} else {
					plugin.getLogman().info("Entity [" + args[0] + "] not found.");
					send("Fail");
				}
			
			// not a command which is supported
			} else {
				plugin.getLogman().warn(c + " is not supported.");
				send("Fail");
			}
			
		} catch (Exception e) {
			
			plugin.getLogman().warn("Error occured handling command");
			e.printStackTrace();
			send("Fail");
			
		}
	}

	// create a cuboid of lots of blocks 
	private void setCuboid(Location pos1, Location pos2, short blockType, short data) {
		int minX, maxX, minY, maxY, minZ, maxZ;
		World world = pos1.getWorld();
		minX = pos1.getBlockX() < pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		maxX = pos1.getBlockX() >= pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		minY = pos1.getBlockY() < pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		maxY = pos1.getBlockY() >= pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		minZ = pos1.getBlockZ() < pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();
		maxZ = pos1.getBlockZ() >= pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();

		for (int x = minX; x <= maxX; ++x) {
			for (int z = minZ; z <= maxZ; ++z) {
				for (int y = minY; y <= maxY; ++y) {
					updateBlock(world, x, y, z, blockType, data);
				}
			}
		}
	}

	// get a cuboid of lots of blocks
	private String getBlocks(Location pos1, Location pos2) {
		StringBuilder blockData = new StringBuilder();

		int minX, maxX, minY, maxY, minZ, maxZ;
		World world = pos1.getWorld();
		minX = pos1.getBlockX() < pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		maxX = pos1.getBlockX() >= pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		minY = pos1.getBlockY() < pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		maxY = pos1.getBlockY() >= pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		minZ = pos1.getBlockZ() < pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();
		maxZ = pos1.getBlockZ() >= pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();

		for (int y = minY; y <= maxY; ++y) {
			 for (int x = minX; x <= maxX; ++x) {
				 for (int z = minZ; z <= maxZ; ++z) {
					blockData.append(new Integer(world.getBlockAt(x, y, z).getTypeId()).toString() + ",");
				}
			}
		}

		return blockData.substring(0, blockData.length() > 0 ? blockData.length() - 1 : 0);	// We don't want last comma
	}

	// updates a block
	private void updateBlock(World world, Location loc, short blockType, short blockData) {
		Block thisBlock = world.getBlockAt(loc);
		updateBlock(thisBlock, blockType, blockData);
	}
	
	private void updateBlock(World world, int x, int y, int z, short blockType, short blockData) {
		Block thisBlock = world.getBlockAt(x,y,z);
		updateBlock(thisBlock, blockType, blockData);
	}
	
	private void updateBlock(Block thisBlock, short blockType, short blockData) {
		// check to see if the block is different - otherwise leave it 
		if ((thisBlock.getTypeId() != blockType) || (thisBlock.getData() != blockData)) {
			thisBlock.setTypeId(blockType);
			thisBlock.setData(blockData);
			thisBlock.update();
		}
	}
	
	// gets the current player
	public Player getCurrentPlayer(String name) {
		// if a named player is returned use that
		Player player = plugin.getNamedPlayer(name);
		// otherwise if there is an attached player for this session use that
		if (player == null) {
			player = attachedPlayer;
			// otherwise go and get the host player and make that the attached player
			if (player == null) {
				player = plugin.getHostPlayer();
				attachedPlayer = player;
			}
		}
		return player;
	}

	public Location parseRelativeBlockLocation(String xstr, String ystr, String zstr) {
		int x = (int) Double.parseDouble(xstr);
		int y = (int) Double.parseDouble(ystr);
		int z = (int) Double.parseDouble(zstr);
		return new Location(plugin.getWorld(), origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z, 0f, 0f);
	}

	public Location parseRelativeLocation(String xstr, String ystr, String zstr) {
		double x = Double.parseDouble(xstr);
		double y = Double.parseDouble(ystr);
		double z = Double.parseDouble(zstr);
		return new Location(plugin.getWorld(), origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z, 0f, 0f);
	}

	public Location parseRelativeBlockLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeBlockLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setRotation(yaw);
		return loc;
	}

	public Location parseRelativeLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setRotation(yaw);
		return loc;
	}
	
	public String blockLocationToRelative(Location loc) {
		return (loc.getBlockX() - origin.getBlockX()) + "," + (loc.getBlockY() - origin.getBlockY()) + "," +
			(loc.getBlockZ() - origin.getBlockZ());
	}

	public String locationToRelative(Location loc) {
		return (loc.getX() - origin.getX()) + "," + (loc.getY() - origin.getY()) + "," +
			(loc.getZ() - origin.getZ());
	}

	// creates a unit vector from rotation and pitch
	public Vector3D getDirection(Player player) {
		double rotation = Math.toRadians(player.getLocation().getRotation());
		double pitch = Math.toRadians(player.getLocation().getPitch());
		double x = (Math.sin(rotation) * Math.cos(pitch)) * -1;
		double y = Math.sin(pitch) * -1;
		double z = Math.cos(rotation) * Math.cos(pitch);
		return new Vector3D(x,y,z);
	}
	
	public void send(Object a) {
		send(a.toString());
	}

	public void send(String a) {
		if (pendingRemoval) return;
		synchronized(outQueue) {
			outQueue.add(a);
		}
	}

	public void close() {
		if (closed) return;
		running = false;
		pendingRemoval = true;
		//wait for threads to stop
		try {
			inThread.join(2000);
			outThread.join(2000);
		}
		catch (InterruptedException e) {
			plugin.getLogman().warn("Failed to stop in/out thread");
			e.printStackTrace();
		}
		
		//close socket
		try {
			socket.close();
		} catch (Exception e) {
			plugin.getLogman().warn("Failed to close socket");
			e.printStackTrace();
		}
		
		closed = true;
		plugin.getLogman().info("Closed connection to" + socket.getRemoteSocketAddress() + ".");
	}

	public void kick(String reason) {
		try {
			out.write(reason);
			out.flush();
		} catch (Exception e) {
		}
		close();
	}

	// add a block hit to the queue to be processed
	public void queueBlockHit(BlockRightClickHook hitHook) {
		blockHitQueue.add(hitHook);
	}
	
	// add a chat posted to the queue to be processed
	public void queueChatPost(ChatHook chatHook) {
		chatPostedQueue.add(chatHook);
	}
	
	/** socket listening thread */
	private class InputThread implements Runnable {
		public void run() {
			plugin.getLogman().info("Starting input thread");
			while (running) {
				try {
					String newLine = in.readLine();
					//System.out.println(newLine);
					if (newLine == null) {
						running = false;
					} else {
						inQueue.add(newLine);
						//System.out.println("Added to in queue");
					}
				} catch (Exception e) {
					// if its running raise an error
					if (running) {
						e.printStackTrace();
						running = false;
					}
				}
			}
			//close in buffer
			try {
				in.close();
			} catch (Exception e) {
				plugin.getLogman().warn("Failed to close in buffer");
				e.printStackTrace();
			}
		}
	}

	private class OutputThread implements Runnable {
		public void run() {
			plugin.getLogman().info("Starting output thread");
			while (running) {
				try {
					String line;
					while((line = outQueue.poll()) != null) {
						out.write(line);
						out.write('\n');
					}
					out.flush();
					Thread.yield();
					Thread.sleep(1L);
				} catch (Exception e) {
					// if its still running raise an error
					if (running) {
						e.printStackTrace();
						running = false;
					}
				}
			}
			//close out buffer
			try {
				out.close();
			} catch (Exception e) {
				plugin.getLogman().warn("Failed to close out buffer");
				e.printStackTrace();
			}
		}
	}

	// turn block faces to numbers
	public static int blockFaceToNotch(BlockFace face) {
		switch (face) {
		case BOTTOM:
			return 0;
		case TOP:
			return 1;
		case NORTH:
			return 2;
		case SOUTH:
			return 3;
		case WEST:
			return 4;
		case EAST:
			return 5;
		default:
			return 7; // Good as anything here, but technically invalid
		}
	}

}
