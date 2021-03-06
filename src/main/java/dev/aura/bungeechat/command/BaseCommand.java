package dev.aura.bungeechat.command;

import java.util.List;

import dev.aura.bungeechat.permission.Permission;
import net.md_5.bungee.api.plugin.Command;

public abstract class BaseCommand extends Command {
    protected static String[] stringListToArray(List<String> list) {
        return list.toArray(new String[list.size()]);
    }

    public BaseCommand(String name) {
        this(name, "");
    }

    public BaseCommand(String name, Permission permission) {
        this(name, permission.getStringedPermission(), new String[0]);
    }

    public BaseCommand(String name, String permission) {
        this(name, permission, new String[0]);
    }

    public BaseCommand(String name, List<String> aliases) {
        this(name, "", aliases);
    }

    public BaseCommand(String name, String[] aliases) {
        this(name, "", aliases);
    }

    public BaseCommand(String name, String permission, List<String> aliases) {
        this(name, permission, stringListToArray(aliases));
    }

    public BaseCommand(String name, String permission, String[] aliases) {
        super(name, permission, aliases);
    }
}
