package dev.aura.bungeechat.account;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import dev.aura.bungeechat.api.account.BungeeChatAccount;
import dev.aura.bungeechat.api.account.BungeeChatAccountStorage;
import dev.aura.bungeechat.api.enums.ChannelType;
import dev.aura.bungeechat.util.LoggerHelper;
import lombok.Cleanup;
import lombok.NonNull;

public class AccountSQLStorage implements BungeeChatAccountStorage {
    private final Connection connection;
    private final String tablePrefix;

    private final String tableAccounts;
    private final String tableAccountsColumnUUID;
    private final String tableAccountsColumnChannelType;
    private final String tableAccountsColumnVanished;
    private final String tableAccountsColumnMessenger;
    private final String tableAccountsColumnSocialSpy;
    private final String tableAccountsColumnLocalSpy;
    private final String tableAccountsColumnMutedUntil;
    private final String tableAccountsColumnStoredPrefix;
    private final String tableAccountsColumnStoredSuffix;
    private final String tableIgnores;
    private final String tableIgnoresColumnUser;
    private final String tableIgnoresColumnIgnores;

    @NonNull
    private PreparedStatement saveAccount;
    @NonNull
    private PreparedStatement loadAccount;
    @NonNull
    private PreparedStatement deleteIgnores;
    @NonNull
    private PreparedStatement addIgnore;
    @NonNull
    private PreparedStatement getIgnores;

    private static byte[] getBytesFromUUID(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());

        return bb.array();
    }

    private static UUID getUUIDFromBytes(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long mostSigBits = bb.getLong();
        long leastSigBits = bb.getLong();

        return new UUID(mostSigBits, leastSigBits);
    }

    public AccountSQLStorage(String ip, int port, String database, String username, String password, String tablePrefix)
            throws SQLException {
        this.tablePrefix = tablePrefix;

        tableAccounts = getTableName("Accounts");
        tableAccountsColumnUUID = "UUID";
        tableAccountsColumnChannelType = "ChannelType";
        tableAccountsColumnVanished = "Vanished";
        tableAccountsColumnMessenger = "Messenger";
        tableAccountsColumnSocialSpy = "SocialSpy";
        tableAccountsColumnLocalSpy = "SocialSpy";
        tableAccountsColumnMutedUntil = "MutedUntil";
        tableAccountsColumnStoredPrefix = "StoredPrefix";
        tableAccountsColumnStoredSuffix = "StoredSuffix";
        tableIgnores = getTableName("Ignores");
        tableIgnoresColumnUser = "User";
        tableIgnoresColumnIgnores = "Ignores";

        String host = "jdbc:mysql://" + ip + ":" + port + "/" + database
                + "?connectTimeout=0&socketTimeout=0&autoReconnect=true";

        connection = DriverManager.getConnection(host, username, password);

        prepareTables();
        prepareStatements();
    }

    @Override
    public void save(BungeeChatAccount account) {
        try {
            byte[] uuidBytes = getBytesFromUUID(account.getUniqueId());

            // deleteIgnores
            deleteIgnores.setBytes(1, uuidBytes);

            deleteIgnores.execute();
            deleteIgnores.clearParameters();

            // saveAccount
            saveAccount.setBytes(1, uuidBytes);
            saveAccount.setString(2, account.getChannelType().name());
            saveAccount.setBoolean(3, account.isVanished());
            saveAccount.setBoolean(4, account.hasMessangerEnabled());
            saveAccount.setBoolean(5, account.hasSocialSpyEnabled());
            saveAccount.setBoolean(6, account.hasLocalSpyEnabled());
            saveAccount.setTimestamp(7, account.getMutedUntil());
            saveAccount.setString(8, account.getStoredPrefix().orElse(null));
            saveAccount.setString(9, account.getStoredSuffix().orElse(null));

            saveAccount.executeUpdate();
            saveAccount.clearParameters();

            // addIgnore
            addIgnore.setBytes(1, uuidBytes);

            for (UUID uuid : account.getIgnored()) {
                addIgnore.setBytes(2, getBytesFromUUID(uuid));

                addIgnore.executeUpdate();
            }

            addIgnore.clearParameters();
        } catch (SQLException e) {
            LoggerHelper.error("Could not save user " + account.getUniqueId() + " to database!", e);
        }
    }

    @Override
    public Entry<BungeeChatAccount, Boolean> load(UUID uuid) {
        try {
            byte[] uuidBytes = getBytesFromUUID(uuid);

            // loadAccount
            loadAccount.setBytes(1, uuidBytes);

            @Cleanup
            ResultSet resultLoadAccount = loadAccount.executeQuery();
            loadAccount.clearParameters();

            if (!resultLoadAccount.next())
                return new SimpleEntry<>(new Account(uuid), true);

            // getIgnores
            getIgnores.setBytes(1, uuidBytes);

            @Cleanup
            ResultSet resultGetIgnores = getIgnores.executeQuery();
            getIgnores.clearParameters();

            BlockingQueue<UUID> ignores = new LinkedBlockingQueue<>();

            while (resultGetIgnores.next()) {
                ignores.add(getUUIDFromBytes(resultGetIgnores.getBytes(tableIgnoresColumnIgnores)));
            }

            return new SimpleEntry<>(
                    new Account(uuid, ChannelType.valueOf(resultLoadAccount.getString(tableAccountsColumnChannelType)),
                            resultLoadAccount.getBoolean(tableAccountsColumnVanished),
                            resultLoadAccount.getBoolean(tableAccountsColumnMessenger),
                            resultLoadAccount.getBoolean(tableAccountsColumnSocialSpy),
                            resultLoadAccount.getBoolean(tableAccountsColumnLocalSpy), ignores,
                            resultLoadAccount.getTimestamp(tableAccountsColumnMutedUntil),
                            Optional.ofNullable(resultLoadAccount.getString(tableAccountsColumnStoredPrefix)),
                            Optional.ofNullable(resultLoadAccount.getString(tableAccountsColumnStoredSuffix))),
                    false);
        } catch (SQLException e) {
            LoggerHelper.error("Could not load user " + uuid + " from database!", e);

            return new SimpleEntry<>(new Account(uuid), true);
        }
    }

    @Override
    public boolean requiresConsoleAccountSave() {
        return true;
    }

    private boolean isConnectionActive() {
        try {
            return (connection != null) && connection.isValid(0);
        } catch (SQLException e) {
            e.printStackTrace();

            return false;
        }
    }

    private Statement getStatement() throws SQLException {
        if (!isConnectionActive())
            throw new SQLException("MySQL-connection is not active!");

        return connection.createStatement();
    }

    private PreparedStatement getPreparedStatement(String statement) throws SQLException {
        return connection.prepareStatement(statement);
    }

    @SuppressWarnings("unused")
    private ResultSet executeQuery(String query) throws SQLException {
        @Cleanup
        Statement statement = getStatement();

        return statement.executeQuery(query);
    }

    private boolean executeStatement(String query) throws SQLException {
        @Cleanup
        Statement statement = getStatement();

        return statement.execute(query);
    }

    @SuppressWarnings("unused")
    private int executeUpdate(String query) throws SQLException {
        @Cleanup
        Statement statement = getStatement();

        return statement.executeUpdate(query);
    }

    private String getTableName(String baseName) {
        String name = tablePrefix + baseName;
        name = '`' + name.replaceAll("`", "``") + '`';

        return name;
    }

    private void prepareTables() {
        try {
            String channelTypeEnum = Arrays.stream(ChannelType.values()).map(ChannelType::name)
                    .collect(Collectors.joining("','", " ENUM('", "')"));

            String createAccountsTable = "CREATE TABLE IF NOT EXISTS " + tableAccounts + " (" + tableAccountsColumnUUID
                    + " BINARY(16) NOT NULL, " + tableAccountsColumnChannelType + channelTypeEnum + " NOT NULL, "
                    + tableAccountsColumnVanished + " BOOLEAN NOT NULL, " + tableAccountsColumnMessenger
                    + " BOOLEAN NOT NULL, " + tableAccountsColumnSocialSpy + " BOOLEAN NOT NULL, "
                    + tableAccountsColumnLocalSpy + " BOOLEAN NOT NULL, " + tableAccountsColumnMutedUntil
                    + " DATETIME NOT NULL, " + tableAccountsColumnStoredPrefix + " TEXT, "
                    + tableAccountsColumnStoredSuffix + " TEXT, PRIMARY KEY (" + tableAccountsColumnUUID
                    + ")) DEFAULT CHARSET=utf8";
            String createIgnoresTable = "CREATE TABLE IF NOT EXISTS " + tableIgnores + " (" + tableIgnoresColumnUser
                    + " BINARY(16) NOT NULL, " + tableIgnoresColumnIgnores + " BINARY(16) NOT NULL, PRIMARY KEY ("
                    + tableIgnoresColumnUser + ", " + tableIgnoresColumnIgnores + "), KEY (" + tableIgnoresColumnUser
                    + "), KEY (" + tableIgnoresColumnIgnores + "), CONSTRAINT FOREIGN KEY (" + tableIgnoresColumnUser
                    + ") REFERENCES " + tableAccounts + " (" + tableAccountsColumnUUID + "), CONSTRAINT FOREIGN KEY ("
                    + tableIgnoresColumnIgnores + ") REFERENCES " + tableAccounts + " (" + tableAccountsColumnUUID
                    + ")) DEFAULT CHARSET=utf8";

            executeStatement(createAccountsTable);
            executeStatement(createIgnoresTable);
        } catch (SQLException e) {
            LoggerHelper.error("Could not create tables!", e);
        }
    }

    private void prepareStatements() {
        try {
            String saveAccountStr = "INSERT INTO " + tableAccounts + " (" + tableAccountsColumnUUID + ", "
                    + tableAccountsColumnChannelType + ", " + tableAccountsColumnVanished + ", "
                    + tableAccountsColumnMessenger + ", " + tableAccountsColumnSocialSpy + ", "
                    + tableAccountsColumnLocalSpy + ", " + tableAccountsColumnMutedUntil + ", "
                    + tableAccountsColumnStoredPrefix + ", " + tableAccountsColumnStoredSuffix
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " + tableAccountsColumnChannelType
                    + " = VALUES(" + tableAccountsColumnChannelType + "), " + tableAccountsColumnVanished + " = VALUES("
                    + tableAccountsColumnVanished + "), " + tableAccountsColumnMessenger + " = VALUES("
                    + tableAccountsColumnMessenger + "), " + tableAccountsColumnSocialSpy + " = VALUES("
                    + tableAccountsColumnSocialSpy + "), " + tableAccountsColumnLocalSpy + " = VALUES("
                    + tableAccountsColumnLocalSpy + "), " + tableAccountsColumnMutedUntil + " = VALUES("
                    + tableAccountsColumnMutedUntil + "), " + tableAccountsColumnStoredPrefix + " = VALUES("
                    + tableAccountsColumnStoredPrefix + "), " + tableAccountsColumnStoredSuffix + " = VALUES("
                    + tableAccountsColumnStoredSuffix + ")";
            String loadAccountStr = "SELECT " + tableAccountsColumnChannelType + ", " + tableAccountsColumnVanished
                    + ", " + tableAccountsColumnMessenger + ", " + tableAccountsColumnSocialSpy + ", "
                    + tableAccountsColumnLocalSpy + ", " + tableAccountsColumnMutedUntil + ", "
                    + tableAccountsColumnStoredPrefix + ", " + tableAccountsColumnStoredSuffix + " FROM "
                    + tableAccounts + " WHERE " + tableAccountsColumnUUID + " = ? LIMIT 1";
            String deleteIgnoresStr = "DELETE FROM " + tableIgnores + " WHERE " + tableIgnoresColumnUser + " = ?";
            String addIgnoreStr = "INSERT INTO " + tableIgnores + " (" + tableIgnoresColumnUser + ", "
                    + tableIgnoresColumnIgnores + ") VALUES (?, ?)";
            String getIgnoresStr = "SELECT " + tableIgnoresColumnIgnores + " FROM " + tableIgnores + " WHERE "
                    + tableIgnoresColumnUser + " = ? ";

            saveAccount = getPreparedStatement(saveAccountStr);
            loadAccount = getPreparedStatement(loadAccountStr);
            deleteIgnores = getPreparedStatement(deleteIgnoresStr);
            addIgnore = getPreparedStatement(addIgnoreStr);
            getIgnores = getPreparedStatement(getIgnoresStr);
        } catch (SQLException e) {
            LoggerHelper.error("Could not prepare statements!", e);
        }
    }
}
