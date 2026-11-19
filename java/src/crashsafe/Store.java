package crashsafe;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Append-only key-value store with checksummed records and atomic compaction.
 *
 * Record layout: magic(2) | payload length(4) | CRC32(payload)(4) | payload,
 * where payload is key length(2) | key | value length(4) | value. A record is
 * accepted only when the magic matches, the length fits in the remaining bytes,
 * and the checksum verifies; anything else ends recovery at the previous record.
 *
 * That rule is the whole safety argument: a torn write can produce a short
 * record, a garbage length, or a bad checksum, and all three look the same to the
 * reader -- "stop here".
 */
public final class Store {
    static final short MAGIC = (short) 0xA5C3;
    static final int HEADER = 10;
    static final String LOG = "log.dat";
    static final String SNAPSHOT = "snapshot.dat";

    record State(Map<String, String> data, int records, long validBytes, long fileBytes) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Store write|recover|compact|stress ...");
            System.exit(2);
        }
        String command = args[0];
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        Path dir = Path.of(options.getOrDefault("dir", "data/store"));
        switch (command) {
            case "write" -> write(dir, Integer.parseInt(options.getOrDefault("records", "1000")),
                Long.parseLong(options.getOrDefault("seed", "7")), options.get("plan"));
            case "recover" -> {
                long crashAt = Long.parseLong(options.getOrDefault("crash-at", "-1"));
                if (crashAt >= 0) {
                    truncate(dir.resolve(LOG), crashAt);
                }
                State state = recover(dir);
                report(state, options.get("json"), options.get("dump"));
            }
            case "compact" -> compact(dir, options.containsKey("crash-before-rename"),
                options.containsKey("crash-after-rename"));
            case "stress" -> stress(dir, Integer.parseInt(options.getOrDefault("trials", "200")),
                Long.parseLong(options.getOrDefault("seed", "7")),
                Integer.parseInt(options.getOrDefault("records", "300")));
            default -> {
                System.err.println("unknown command: " + command);
                System.exit(2);
            }
        }
    }

    static void write(Path dir, int records, long seed, String planPath) throws IOException {
        Files.createDirectories(dir);
        Path log = dir.resolve(LOG);
        Files.deleteIfExists(log);
        Files.deleteIfExists(dir.resolve(SNAPSHOT));
        Random random = new Random(seed);
        List<long[]> offsets = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(log, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             OutputStream out = new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(channel))) {
            long position = 0;
            for (int index = 0; index < records; index++) {
                String key = String.format(Locale.ROOT, "k%05d", index % 400);
                String value = "v" + random.nextInt(1_000_000) + "-" + "x".repeat(random.nextInt(40));
                byte[] record = encode(key, value);
                offsets.add(new long[]{position, position + record.length});
                keys.add(key);
                values.add(value);
                out.write(record);
                position += record.length;
            }
            out.flush();
            channel.force(true);
        }
        if (planPath != null) {
            StringBuilder json = new StringBuilder("{\"records\":[");
            for (int index = 0; index < offsets.size(); index++) {
                if (index > 0) json.append(',');
                json.append("{\"key\":").append(quote(keys.get(index)))
                    .append(",\"value\":").append(quote(values.get(index)))
                    .append(",\"start\":").append(offsets.get(index)[0])
                    .append(",\"end\":").append(offsets.get(index)[1]).append('}');
            }
            json.append("]}\n");
            Files.createDirectories(Path.of(planPath).toAbsolutePath().getParent());
            Files.writeString(Path.of(planPath), json.toString(), StandardCharsets.UTF_8);
        }
        System.out.println(String.format(Locale.ROOT, "wrote %d records, %d bytes to %s",
            records, Files.size(log), log));
    }

    static byte[] encode(String key, String value) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(2 + keyBytes.length + 4 + valueBytes.length);
        payload.putShort((short) keyBytes.length).put(keyBytes)
            .putInt(valueBytes.length).put(valueBytes);
        byte[] body = payload.array();
        CRC32 crc = new CRC32();
        crc.update(body);
        ByteBuffer record = ByteBuffer.allocate(HEADER + body.length);
        record.putShort(MAGIC).putInt(body.length).putInt((int) crc.getValue()).put(body);
        return record.array();
    }

    static State recover(Path dir) throws IOException {
        Map<String, String> data = new TreeMap<>();
        // A crash during compaction leaves the temporary snapshot behind; it is
        // never authoritative, because the rename is the commit point.
        Files.deleteIfExists(dir.resolve(SNAPSHOT + ".tmp"));
        long validBytes = 0;
        int records = 0;
        Path snapshot = dir.resolve(SNAPSHOT);
        if (Files.isRegularFile(snapshot)) {
            State fromSnapshot = replay(snapshot, data);
            validBytes = fromSnapshot.validBytes();
            records = fromSnapshot.records();
        }
        Path log = dir.resolve(LOG);
        long fileBytes = Files.isRegularFile(log) ? Files.size(log) : 0;
        if (fileBytes > 0) {
            State fromLog = replay(log, data);
            validBytes = fromLog.validBytes();
            records += fromLog.records();
        }
        return new State(data, records, validBytes, fileBytes);
    }

    static State replay(Path file, Map<String, String> data) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int offset = 0;
        int records = 0;
        while (offset + HEADER <= bytes.length) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, HEADER);
            short magic = buffer.getShort();
            if (magic != MAGIC) {
                break;
            }
            int length = buffer.getInt();
            int checksum = buffer.getInt();
            if (length < 0 || offset + HEADER + length > bytes.length) {
                break;
            }
            CRC32 crc = new CRC32();
            crc.update(bytes, offset + HEADER, length);
            if ((int) crc.getValue() != checksum) {
                break;
            }
            ByteBuffer payload = ByteBuffer.wrap(bytes, offset + HEADER, length);
            short keyLength = payload.getShort();
            if (keyLength < 0 || keyLength > payload.remaining()) {
                break;
            }
            byte[] key = new byte[keyLength];
            payload.get(key);
            int valueLength = payload.getInt();
            if (valueLength < 0 || valueLength > payload.remaining()) {
                break;
            }
            byte[] value = new byte[valueLength];
            payload.get(value);
            data.put(new String(key, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8));
            offset += HEADER + length;
            records++;
        }
        // A real store repairs the tail on open, so the next append cannot land
        // behind a torn record.
        if (offset < bytes.length) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(offset);
                channel.force(true);
            }
        }
        return new State(data, records, offset, bytes.length);
    }

    static void compact(Path dir, boolean crashBeforeRename, boolean crashAfterRename) throws IOException {
        State state = recover(dir);
        Path snapshot = dir.resolve(SNAPSHOT);
        Path temporary = dir.resolve(SNAPSHOT + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Map.Entry<String, String> entry : state.data().entrySet()) {
                out.write(encode(entry.getKey(), entry.getValue()));
            }
        }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        if (crashBeforeRename) {
            System.out.println("crashed before rename; temporary snapshot left behind");
            return;
        }
        Files.move(temporary, snapshot, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (crashAfterRename) {
            System.out.println("crashed after rename, before truncating the log");
            return;
        }
        Files.deleteIfExists(dir.resolve(LOG));
        System.out.println(String.format(Locale.ROOT, "compacted %d keys into %s", state.data().size(), snapshot));
    }

    static void stress(Path dir, int trials, long seed, int records) throws IOException {
        Path base = dir.resolve("stress");
        Files.createDirectories(base);
        String plan = base.resolve("plan.json").toString();
        write(base, records, seed, plan);
        List<long[]> offsets = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        String planJson = Files.readString(Path.of(plan), StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\\{\"key\":\"([^\"]*)\",\"value\":\"([^\"]*)\",\"start\":(\\d+),\"end\":(\\d+)\\}")
            .matcher(planJson);
        while (matcher.find()) {
            keys.add(matcher.group(1));
            values.add(matcher.group(2));
            offsets.add(new long[]{Long.parseLong(matcher.group(3)), Long.parseLong(matcher.group(4))});
        }
        Path log = base.resolve(LOG);
        byte[] original = Files.readAllBytes(log);
        Random random = new Random(seed * 31 + 17);
        int failures = 0;
        int phantom = 0;
        for (int trial = 0; trial < trials; trial++) {
            int cut = random.nextInt(original.length + 1);
            Files.write(log, java.util.Arrays.copyOf(original, cut));
            Map<String, String> expected = new TreeMap<>();
            int complete = 0;
            for (int index = 0; index < offsets.size(); index++) {
                if (offsets.get(index)[1] <= cut) {
                    expected.put(keys.get(index), values.get(index));
                    complete++;
                }
            }
            State state = recover(base);
            if (!state.data().equals(expected)) {
                failures++;
                for (String key : state.data().keySet()) {
                    if (!expected.containsKey(key)) {
                        phantom++;
                        break;
                    }
                }
            }
            Files.write(log, original);
        }
        System.out.println(String.format(Locale.ROOT,
            "{\"trials\":%d,\"failures\":%d,\"phantom_recoveries\":%d,\"records\":%d,\"bytes\":%d}",
            trials, failures, phantom, records, original.length));
    }

    static void report(State state, String jsonPath, String dumpPath) throws IOException {
        String hash = digest(state.data());
        String summary = String.format(Locale.ROOT,
            "{\"records\":%d,\"keys\":%d,\"valid_bytes\":%d,\"file_bytes\":%d,"
                + "\"repaired_bytes\":%d,\"state_hash\":\"%s\"}\n",
            state.records(), state.data().size(), state.validBytes(), state.fileBytes(),
            Math.max(0, state.fileBytes() - state.validBytes()), hash);
        if (jsonPath != null) {
            Files.writeString(Path.of(jsonPath), summary, StandardCharsets.UTF_8);
        }
        if (dumpPath != null) {
            StringBuilder dump = new StringBuilder();
            for (Map.Entry<String, String> entry : state.data().entrySet()) {
                dump.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
            }
            Files.writeString(Path.of(dumpPath), dump.toString(), StandardCharsets.UTF_8);
        }
        System.out.print(summary);
    }

    static String digest(Map<String, String> data) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : data.entrySet()) {
                sha.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                sha.update((byte) '=');
                sha.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                sha.update((byte) '\n');
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    static void truncate(Path file, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(Math.min(size, channel.size()));
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }
}
