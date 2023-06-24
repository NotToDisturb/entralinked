package entralinked.network.http.pgl;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import entralinked.Configuration;
import entralinked.Entralinked;
import entralinked.model.dlc.DlcList;
import entralinked.model.pkmn.PkmnInfo;
import entralinked.model.pkmn.PkmnInfoReader;
import entralinked.model.player.DreamEncounter;
import entralinked.model.player.DreamItem;
import entralinked.model.player.Player;
import entralinked.model.player.PlayerManager;
import entralinked.model.player.PlayerStatus;
import entralinked.model.user.ServiceSession;
import entralinked.model.user.UserManager;
import entralinked.network.http.HttpHandler;
import entralinked.network.http.HttpRequestHandler;
import entralinked.serialization.UrlEncodedFormFactory;
import entralinked.serialization.UrlEncodedFormParser;
import entralinked.utility.LEOutputStream;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.security.BasicAuthCredentials;
import jakarta.servlet.ServletInputStream;

/**
 * HTTP handler for requests made to {@code en.pokemon-gl.com}
 */
public class PglHandler implements HttpHandler {
    
    private static final Logger logger = LogManager.getLogger();
    private static final String username = "pokemon";
    private static final String password = "2Phfv9MY"; // Best security in the world
    private final ObjectMapper mapper = new ObjectMapper(new UrlEncodedFormFactory()
            .disable(UrlEncodedFormParser.Feature.BASE64_DECODE_VALUES));
    private final Set<Integer> sleepyList = new HashSet<>();
    private final Configuration configuration;
    private final DlcList dlcList;
    private final UserManager userManager;
    private final PlayerManager playerManager;
    
    public PglHandler(Entralinked entralinked) {
        this.configuration = entralinked.getConfiguration();
        this.dlcList = entralinked.getDlcList();
        this.userManager = entralinked.getUserManager();
        this.playerManager = entralinked.getPlayerManager();
        
        // Add all species to the sleepy list
        for(int i = 1; i <= 649; i++) {
            sleepyList.add(i);
        }
    }
    
    @Override
    public void addHandlers(Javalin javalin) {
        javalin.before("/dsio/gw", this::authorizePglRequest);
        javalin.get("/dsio/gw", this::handlePglGetRequest);
        javalin.post("/dsio/gw", this::handlePglPostRequest);
    }
    
    /**
     * BEFORE handler for {@code /dsio/gw} that serves to deserialize and authenticate the request.
     * The deserialized request will be stored in a context attribute named {@code request} and may be retrieved
     * by subsequent handlers.
     */
    private void authorizePglRequest(Context ctx) throws IOException {
        System.out.println(ctx.queryString());
        // Verify the authorization header credentials
        BasicAuthCredentials credentials = ctx.basicAuthCredentials();
        
        if(credentials == null || 
                !username.equals(credentials.getUsername()) || !password.equals(credentials.getPassword())) {
            ctx.status(HttpStatus.UNAUTHORIZED);
            clearTasks(ctx);
            return;
        }
        
        // Deserialize the request
        PglRequest request = mapper.readValue(ctx.queryString(), PglRequest.class);
        
        // Check game version
        if(request.gameVersion() == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            clearTasks(ctx);
            return;
        }
        
        // Verify the service session token
        ServiceSession session = userManager.getServiceSession(request.token(), "external");
        
        if(session == null) {
            ctx.status(HttpStatus.UNAUTHORIZED);
            clearTasks(ctx);
            return;
        }
        
        // Store request object for subsequent handlers
        ctx.attribute("request", request);
    }
    
    /**
     * GET base handler for {@code /dsio/gw}
     */
    private void handlePglGetRequest(Context ctx) throws IOException {
        PglRequest request = ctx.attribute("request");
        
        // Determine request handler function based on type
        HttpRequestHandler<PglRequest> handler = switch(request.type()) {
            case "sleepily.bitlist" -> this::handleGetSleepyList;
            case "account.playstatus" -> this::handleGetAccountStatus;
            case "savedata.download" -> this::handleDownloadSaveData;
            default -> throw new IllegalArgumentException("Invalid GET request type: " + request.type());
        };
        
        // Handle the request
        handler.process(request, ctx);    
    }
    
    /**
     * GET handler for {@code /dsio/gw?p=sleepily.bitlist}
     */
    private void handleGetSleepyList(PglRequest request, Context ctx) throws IOException {
        LEOutputStream outputStream = new LEOutputStream(ctx.outputStream());
        
        // Check if player exists
        if(!playerManager.doesPlayerExist(request.gameSyncId())) {
            writeStatusCode(outputStream, 1); // Unauthorized
            return;
        }
                
        // Create bitlist
        byte[] bitlist = new byte[128]; // TODO pool? maybe just cache
        
        for(int sleepy : sleepyList) {
            // 8 Pokémon (bits) in 1 byte
            int byteOffset = sleepy / 8;
            int bitOffset = sleepy % 8;
            
            // Set the bit to 1!
            bitlist[byteOffset] |= 1 << bitOffset;
        }
        
        // Send bitlist
        writeStatusCode(outputStream, 0);
        outputStream.write(bitlist);
    }
    
    /**
     * GET handler for {@code /dsio/gw?p=account.playstatus}
     * 
     * Black 2 - {@code sub_21B74B4} (overlay #199)
     */
    private void handleGetAccountStatus(PglRequest request, Context ctx) throws IOException {
        LEOutputStream outputStream = new LEOutputStream(ctx.outputStream());
        Player player = playerManager.getPlayer(request.gameSyncId());
        
        // Request account creation if one doesn't exist yet
        if(player == null) {
            writeStatusCode(outputStream, 8); // 5 is also handled separately, but doesn't seem to do anything unique
            return;
        }
        
        writeStatusCode(outputStream, 0);
        outputStream.writeShort(player.getStatus().ordinal());
    }
    
    /**
     * GET handler for {@code /dsio/gw?p=savedata.download}
     * 
     * Black 2 - {@code sub_21B6C9C} (overlay #199)
     */
    private void handleDownloadSaveData(PglRequest request, Context ctx) throws IOException {
        LEOutputStream outputStream = new LEOutputStream(ctx.outputStream());
        Player player = playerManager.getPlayer(request.gameSyncId());
        
        // Check if player exists
        if(player == null) {
            writeStatusCode(outputStream, 1); // Unauthorized
            return;
        }
        
        // Write status code
        writeStatusCode(outputStream, 0);
        
        // Allow it to wake up anyway, maybe the poor sap is stuck..
        // Just don't send any other data.
        if(player.getStatus() == PlayerStatus.AWAKE) {
            logger.info("Player {} is downloading save data, but is already awake!", player.getGameSyncId());
            return;
        }
        
        List<DreamEncounter> encounters = player.getEncounters();
        List<DreamItem> items = player.getItems();
        
         // When waking up a Pokémon, these 4 bytes are written to 0x1D304 in the save file.
         // If the bytes in the game's save file match the new bytes, they will be set to 0x00000000
         // and no content will be downloaded.
        outputStream.writeInt((int)(Math.random() * Integer.MAX_VALUE));
        
        // Write encounter data (max 10)
        for(DreamEncounter encounter : encounters) {
            outputStream.writeShort(encounter.species());
            outputStream.writeShort(encounter.move());
            outputStream.write(encounter.form());
            outputStream.write(0); // unknown
            outputStream.write(encounter.animation().ordinal());
            outputStream.write(0); // unknown
        }
        
        // Write encounter padding
        outputStream.writeBytes(0, (10 - encounters.size()) * 8);
        
        // Write misc stuff and DLC information
        outputStream.writeShort(player.getLevelsGained());
        outputStream.write(0); // Unknown
        outputStream.write(dlcList.getDlcIndex(player.getMusical()));
        outputStream.write(dlcList.getDlcIndex(player.getCGearSkin()));
        outputStream.write(dlcList.getDlcIndex(player.getDexSkin()));
        outputStream.write(0); // Unknown
        outputStream.write(0); // Must be zero?
        
        // Write item IDs
        for(DreamItem item : items) {
            outputStream.writeShort(item.id());
        }
        
        // Write item ID padding
        outputStream.writeBytes(0, (20 - items.size()) * 2);
        
        // Write item quantities
        for(DreamItem item : items) {
            outputStream.write(item.quantity()); // Hard caps at 20?
        }
        
        // Write quantity padding
        outputStream.writeBytes(0, (20 - items.size()));
    }
    
    /**
     * POST base handler for {@code /dsio/gw}
     */
    private void handlePglPostRequest(Context ctx) throws IOException {
        // Retrieve context attributes
        PglRequest request = ctx.attribute("request");
        
        // Determine handler function based on request type
        HttpRequestHandler<PglRequest> handler = switch(request.type()) {
            case "savedata.upload" -> this::handleUploadSaveData; 
            case "savedata.download.finish" -> this::handleDownloadSaveDataFinish;
            case "account.create.upload" -> this::handleCreateAccount;
            default -> throw new IllegalArgumentException("Invalid POST request type: " + request.type());
        };
        
        handler.process(request, ctx);    
    }
    
    /**
     * POST handler for {@code /dsio/gw?p=savedata.download.finish}
     */
    private void handleDownloadSaveDataFinish(PglRequest request, Context ctx) throws IOException {
        LEOutputStream outputStream = new LEOutputStream(ctx.outputStream());
        Player player = playerManager.getPlayer(request.gameSyncId());
        
        // Check if player exists
        if(player == null) {
            writeStatusCode(outputStream, 1); // Unauthorized
            return;
        }
        
        // Reset player dream information if configured to do so
        if(configuration.clearPlayerDreamInfoOnWake()) {
            player.resetDreamInfo();
            
            // Try to save player data
            if(!playerManager.savePlayer(player)) {
                logger.warn("Save data failure for player {}", player.getGameSyncId());
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        
        // Write status code
        writeStatusCode(outputStream, 0);
    }
    
    /**
     * POST handler for {@code /dsio/gw?p=savedata.upload}
     */
    private void handleUploadSaveData(PglRequest request, Context ctx) throws IOException {
        // Read save data
        ServletInputStream inputStream = ctx.req().getInputStream();
        inputStream.skip(0x1D300); // Skip to dream world data
        inputStream.skip(8); // Skip to Pokémon data
        PkmnInfo info = PkmnInfoReader.readPokeInfo(inputStream);
        
        // Don't care about anything else -- continue reading bytes until we're done.
        while(!inputStream.isFinished()) {
            inputStream.read();
        }
        
        // Prepare response
        LEOutputStream outputStream = new LEOutputStream(ctx.outputStream());
        Player player = playerManager.getPlayer(request.gameSyncId());
        
        // Check if player exists
        if(player == null) {
            writeStatusCode(outputStream, 1); // Unauthorized
            return;
        }
        
        // Check if player doesn't already have a Pokémon tucked in
        if(player.getStatus() != PlayerStatus.AWAKE) {
            logger.warn("Player {} tried to upload save data while already asleep", player.getGameSyncId());
            
            // Return error if not allowed
            if(!configuration.allowOverwritingPlayerDreamInfo()) {
                writeStatusCode(outputStream, 4); // Already dreaming
                return;
            }
        }
        
        // Update and save player information
        player.setStatus(PlayerStatus.SLEEPING);
        player.setDreamerInfo(info);
        
        if(!playerManager.savePlayer(player)) {
            logger.warn("Save data failure for player {}", player.getGameSyncId());
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            return;
        }
        
        // Send status code
        writeStatusCode(outputStream, 0);
    }
    
    /**
     * POST handler for {@code /dsio/gw?p=account.create.upload}
     */
    private void handleCreateAccount(PglRequest request, Context ctx) throws IOException {        
        // It sends the entire save file, but we just skip through it because we don't need anything from it here
        ServletInputStream inputStream = ctx.req().getInputStream();
        
        while(!inputStream.isFinished()) {
            inputStream.read();
        }
        
        // Prepare response
        LEOutputStream outputStream = new LEOutputStream(ctx.outputStream());
        String gameSyncId = request.gameSyncId();
        
        // Check if player doesn't exist already
        if(playerManager.doesPlayerExist(gameSyncId)) {
            writeStatusCode(outputStream, 2); // Duplicate Game Sync ID
            return;
        }
        
        // Try to register player
        if(playerManager.registerPlayer(gameSyncId, request.gameVersion()) == null) {
            writeStatusCode(outputStream, 3); // Registration error
            return;
        }
        
        // Write status code
        writeStatusCode(outputStream, 0);
    }
    
    /**
     * Writes the 4-byte status code and 124 empty bytes to the output stream.
     */
    private void writeStatusCode(LEOutputStream outputStream, int status) throws IOException {
        outputStream.writeInt(status);
        outputStream.writeBytes(0, 124);
    }
}
