package com.excelsies.hycolonies.colony.storage;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles JSON persistence for colony data using an atomic write strategy.
 *
 * The atomic write strategy ensures data integrity:
 * 1. Write to temporary file
 * 2. Flush and sync
 * 3. Create backup of existing file
 * 4. Rename temp to final (atomic operation)
 *
 * This prevents data corruption during crashes or power failures.
 */
public class ColonyStorage {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String COLONY_FILE_PREFIX = "colony_";
    private static final String JSON_EXTENSION = ".json";
    private static final String BACKUP_EXTENSION = ".backup";
    private static final String TEMP_EXTENSION = ".tmp";

    private final Path storageDirectory;
    private final Gson gson;

    /**
     * Creates a new ColonyStorage instance.
     *
     * @param serverDirectory The root server directory (typically ./run in development)
     */
    public ColonyStorage(Path serverDirectory) {
        // ./universe/worlds/default/hycolonies/
        this.storageDirectory = serverDirectory
                .resolve("universe")
                .resolve("worlds")
                .resolve("default")
                .resolve("hycolonies");

        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create();

        ensureDirectoryExists();
    }

    private void ensureDirectoryExists() {
        try {
            Files.createDirectories(storageDirectory);
            LOGGER.atInfo().log("Colony storage directory: " + storageDirectory.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.atSevere().withCause(e).log("Failed to create storage directory");
        }
    }

    /**
     * Saves a colony using the atomic write strategy.
     *
     * @param colony The colony data to save
     * @return true if save was successful, false otherwise
     */
    public boolean save(ColonyData colony) {
        String filename = COLONY_FILE_PREFIX + colony.getColonyId() + JSON_EXTENSION;
        Path targetFile = storageDirectory.resolve(filename);
        Path tempFile = storageDirectory.resolve(filename + TEMP_EXTENSION);
        Path backupFile = storageDirectory.resolve(filename + BACKUP_EXTENSION);

        try {
            // Step 1: Write to temporary file
            String json = gson.toJson(colony);
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write(json);
                writer.flush();
            }

            // Step 2: Create backup of existing file (if exists)
            if (Files.exists(targetFile)) {
                Files.copy(targetFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // Step 3: Atomic rename temp -> target
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            LOGGER.atInfo().log("Saved colony: " + colony.getName() + " (" + colony.getColonyId() + ")");
            return true;

        } catch (IOException e) {
            LOGGER.atSevere().withCause(e).log("Failed to save colony: " + colony.getColonyId());

            // Attempt to restore from backup
            if (Files.exists(backupFile)) {
                try {
                    Files.copy(backupFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.atWarning().log("Restored colony from backup after save failure");
                } catch (IOException restoreEx) {
                    LOGGER.atSevere().withCause(restoreEx).log("Failed to restore from backup");
                }
            }
            return false;
        } finally {
            // Cleanup temp file if it still exists
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Loads a colony by its UUID.
     *
     * @param colonyId The UUID of the colony to load
     * @return Optional containing the colony data, or empty if not found
     */
    public Optional<ColonyData> load(UUID colonyId) {
        String filename = COLONY_FILE_PREFIX + colonyId + JSON_EXTENSION;
        Path file = storageDirectory.resolve(filename);

        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            ColonyData colony = gson.fromJson(reader, ColonyData.class);
            LOGGER.atInfo().log("Loaded colony: " + colony.getName());
            return Optional.of(colony);
        } catch (IOException e) {
            LOGGER.atSevere().withCause(e).log("Failed to load colony: " + colonyId);
            return Optional.empty();
        }
    }

    /**
     * Loads all colonies from the storage directory.
     *
     * @return List of all loaded colonies
     */
    public List<ColonyData> loadAll() {
        List<ColonyData> colonies = new ArrayList<>();

        if (!Files.exists(storageDirectory)) {
            return colonies;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDirectory,
                COLONY_FILE_PREFIX + "*" + JSON_EXTENSION)) {

            for (Path file : stream) {
                try (BufferedReader reader = Files.newBufferedReader(file)) {
                    ColonyData colony = gson.fromJson(reader, ColonyData.class);
                    if (colony != null) {
                        colonies.add(colony);
                    }
                } catch (IOException e) {
                    LOGGER.atWarning().withCause(e).log("Failed to load colony file: " + file);
                }
            }
        } catch (IOException e) {
            LOGGER.atSevere().withCause(e).log("Failed to enumerate colony files");
        }

        LOGGER.atInfo().log("Loaded " + colonies.size() + " colonies");
        return colonies;
    }

    /**
     * Deletes a colony file and its backup.
     *
     * @param colonyId The UUID of the colony to delete
     * @return true if deletion was successful, false otherwise
     */
    public boolean delete(UUID colonyId) {
        String filename = COLONY_FILE_PREFIX + colonyId + JSON_EXTENSION;
        Path file = storageDirectory.resolve(filename);
        Path backupFile = storageDirectory.resolve(filename + BACKUP_EXTENSION);

        try {
            boolean deleted = Files.deleteIfExists(file);
            Files.deleteIfExists(backupFile);
            if (deleted) {
                LOGGER.atInfo().log("Deleted colony: " + colonyId);
            }
            return deleted;
        } catch (IOException e) {
            LOGGER.atSevere().withCause(e).log("Failed to delete colony: " + colonyId);
            return false;
        }
    }

    /**
     * Gets the storage directory path.
     */
    public Path getStorageDirectory() {
        return storageDirectory;
    }
}
