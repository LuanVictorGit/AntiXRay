package com.lhawk.antixray;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

// Diagnostico de por que o PacketEvents nao entrega pacotes ao AntiXray (aparece no /antixray status)
final class Diagnostics {

    private static volatile String embeddedCopies = "verificando...";
    private static volatile List<String> packetEventsLog = Collections.emptyList();

    private Diagnostics() {
    }

    // Le os jars dos plugins e o latest.log numa thread propria para nao travar o servidor
    static void scanInBackground(final AntiXray plugin) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                embeddedCopies = findEmbeddedPacketEvents(plugin);
                packetEventsLog = findPacketEventsLogLines();
                plugin.getLogger().info("Plugins com PacketEvents embutido: " + embeddedCopies);
                for (String line : packetEventsLog) {
                    plugin.getLogger().info("Aviso do PacketEvents no log: " + line);
                }
            }
        }, "AntiXray-Diagnostics");
        thread.setDaemon(true);
        thread.start();
    }

    static String getEmbeddedCopies() {
        return embeddedCopies;
    }

    static List<String> getPacketEventsLog() {
        return packetEventsLog;
    }

    // Plugins que trazem uma copia propria do PacketEvents no jar: podem disputar a conexao com o plugin packetevents
    private static String findEmbeddedPacketEvents(AntiXray self) {
        List<String> found = new ArrayList<String>();
        for (Plugin p : self.getServer().getPluginManager().getPlugins()) {
            if (p.getName().equalsIgnoreCase("packetevents")) continue;
            try {
                File file = new File(p.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
                if (!file.isFile()) continue;
                JarFile jar = new JarFile(file);
                try {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.endsWith("/PacketEventsAPI.class")) {
                            found.add(p.getName() + (name.startsWith("com/github/retrooper/") ? " (SEM relocate)" : ""));
                            break;
                        }
                    }
                } finally {
                    jar.close();
                }
            } catch (Throwable ignored) {
            }
        }
        if (self.getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            found.add("ProtocolLib (outro injetor de pacotes)");
        }
        return found.isEmpty() ? "nenhum" : found.toString();
    }

    // Avisos e erros do PacketEvents desde que o servidor ligou
    private static List<String> findPacketEventsLogLines() {
        File log = new File("logs", "latest.log");
        if (!log.isFile()) return Collections.singletonList("logs/latest.log nao encontrado");
        List<String> lines = new ArrayList<String>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null && lines.size() < 5) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.contains("packetevents") && (lower.contains("warn") || lower.contains("error")
                        || lower.contains("fail") || lower.contains("exception") || lower.contains("inject"))) {
                        lines.add(line.length() > 220 ? line.substring(0, 220) + "..." : line);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            lines.add("erro ao ler o log: " + t);
        }
        return lines;
    }

    // Le os canais Netty direto da conexao do jogador, sem passar pelo PacketEvents
    static String describeRealChannel(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object listener = readField(handle, "connection");
            Object connection = readField(listener, "connection");
            ClassLoader loader = connection.getClass().getClassLoader();
            Class<?> channelClass = Class.forName("io.netty.channel.Channel", false, loader);
            Method isOpen = channelClass.getMethod("isOpen");
            Method pipeline = channelClass.getMethod("pipeline");
            Method names = Class.forName("io.netty.channel.ChannelPipeline", false, loader).getMethod("names");

            StringBuilder sb = new StringBuilder();
            for (Class<?> c = connection.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!channelClass.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object channel = f.get(connection);
                    if (sb.length() > 0) sb.append(" || ");
                    sb.append(f.getName()).append(": ");
                    if (channel == null) {
                        sb.append("null");
                    } else {
                        sb.append("aberto=").append(isOpen.invoke(channel)).append(' ').append(names.invoke(pipeline.invoke(channel)));
                    }
                }
            }
            return sb.length() == 0 ? "nenhum campo Channel na conexao" : sb.toString();
        } catch (Throwable t) {
            return "erro ao inspecionar: " + t;
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name + " em " + target.getClass().getName());
    }
}
