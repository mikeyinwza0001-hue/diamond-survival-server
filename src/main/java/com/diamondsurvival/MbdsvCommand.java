package com.diamondsurvival;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class MbdsvCommand implements CommandExecutor, TabCompleter {

    private final GameCommands cmds;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "start", "stop", "add", "remove", "reset", "wrap", "scan", "time", "set", "death", "mob", "tnt", "lava"
    );

    public MbdsvCommand(GameCommands cmds) {
        this.cmds = cmds;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§b[DiamondSurvival] §7คำสั่งที่ใช้ได้:");
            sender.sendMessage("§e /mbdsv start §7- เริ่มเกม (+ Night Vision)");
            sender.sendMessage("§e /mbdsv stop §7- หยุดเกมทั้งหมด");
            sender.sendMessage("§e /mbdsv add <amount> §7- เพิ่มเพชร");
            sender.sendMessage("§e /mbdsv remove <amount> §7- ลบเพชร");
            sender.sendMessage("§e /mbdsv reset §7- รีเซ็ตเพชรทั้งหมด");
            sender.sendMessage("§e /mbdsv wrap §7- สุ่มไปเมืองรอบๆ");
            sender.sendMessage("§e /mbdsv scan [seconds] §7- แสดงกรอบสีขาวตรงเพชร");
            sender.sendMessage("§e /mbdsv time <seconds> §7- กำหนดเวลานับถอยหลัง");
            sender.sendMessage("§e /mbdsv set <amount> §7- กำหนดเป้าหมายเพชร");
            sender.sendMessage("§e /mbdsv death <on|off> §7- เปิด/ปิด drop ของตอนตาย");
            sender.sendMessage("§e /mbdsv mob <name> <count> §7- เสก Mob");
            sender.sendMessage("§e /mbdsv tnt <count> §7- เสก TNT ระเบิด 1 วิ");
            sender.sendMessage("§e /mbdsv lava <count> §7- เสก Lava");
            return true;
        }

        String sub = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        return switch (sub) {
            case "start" -> cmds.start(sender);
            case "stop" -> cmds.stop(sender);
            case "add" -> cmds.add(sender, subArgs);
            case "remove" -> cmds.remove(sender, subArgs);
            case "reset" -> cmds.reset(sender);
            case "wrap" -> cmds.wrap(sender);
            case "scan" -> cmds.scan(sender, subArgs);
            case "time" -> cmds.time(sender, subArgs);
            case "set" -> cmds.setGoal(sender, subArgs);
            case "death" -> cmds.death(sender, subArgs);
            case "mob" -> cmds.mob(sender, subArgs);
            case "tnt" -> cmds.tnt(sender, subArgs);
            case "lava" -> cmds.lava(sender, subArgs);
            default -> {
                sender.sendMessage("§c[DiamondSurvival] ไม่รู้จักคำสั่ง: " + sub);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
