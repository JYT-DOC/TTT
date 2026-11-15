import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 记账本本地服务：只提供「静态页面 + 记录增删改查 + JSON 落盘」，零第三方依赖。
 * 运行：java ledger/LedgerServer.java   （可选第二个参数指定端口，默认 8787）
 */
public final class LedgerServer {

    private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$");
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final List<String> TYPES = List.of("expense", "income");
    private static final Object LOCK = new Object();
    private static final List<Map<String, Object>> RECORDS = new ArrayList<>();

    private static Path webRoot;
    private static Path dataFile;

    public static void main(String[] args) throws Exception {
        webRoot = findWebRoot();
        dataFile = webRoot.resolve("data").resolve("records.json");

        int port = 8787;
        String override = args.length > 0 ? args[0] : System.getenv("LEDGER_PORT");
        if (override != null && !override.isBlank()) {
            port = Integer.parseInt(override.trim());
        }

        load();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", LedgerServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("记账本已启动 → http://localhost:" + port + "/");
        System.out.println("数据文件     → " + dataFile);
        System.out.println("停止服务     → Ctrl + C");
    }

    private static Path findWebRoot() {
        String home = System.getenv("LEDGER_HOME");
        if (home != null && !home.isBlank()) {
            return Paths.get(home).toAbsolutePath().normalize();
        }
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path nested = cwd.resolve("ledger");
        if (Files.isRegularFile(nested.resolve("index.html"))) {
            return nested;
        }
        return cwd;
    }

    // ---------- 路由 ----------

    private static void handle(HttpExchange exchange) {
        try {
            String path = decode(exchange.getRequestURI().getPath());
            if (path.startsWith("/api/")) {
                handleApi(exchange, path);
            } else {
                handleStatic(exchange, path);
            }
        } catch (Exception e) {
            try {
                send(exchange, 500, "application/json; charset=utf-8", jsonOf("error", "服务内部错误"));
            } catch (IOException ignored) {
                // 连接已断开，无法回报
            }
        } finally {
            exchange.close();
        }
    }

    private static void handleApi(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();

        if ("/api/health".equals(path)) {
            send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/api/records".equals(path)) {
            if ("GET".equals(method)) {
                String body;
                synchronized (LOCK) {
                    body = Json.write(RECORDS);
                }
                send(exchange, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("POST".equals(method)) {
                Map<String, Object> input = readObject(exchange);
                try {
                    Map<String, Object> saved = synchronizedCreate(input);
                    send(exchange, 201, "application/json; charset=utf-8", Json.write(saved).getBytes(StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    send(exchange, 400, "application/json; charset=utf-8", jsonOf("error", e.getMessage()));
                }
                return;
            }
            send(exchange, 405, "application/json; charset=utf-8", jsonOf("error", "方法不允许"));
            return;
        }

        if (path.startsWith("/api/records/")) {
            String id = path.substring("/api/records/".length());
            if (!ID_PATTERN.matcher(id).matches()) {
                send(exchange, 400, "application/json; charset=utf-8", jsonOf("error", "非法 id"));
                return;
            }
            if ("PUT".equals(method)) {
                Map<String, Object> input = readObject(exchange);
                input.put("id", id);
                try {
                    Map<String, Object> saved = synchronizedUpdate(id, input);
                    if (saved == null) {
                        send(exchange, 404, "application/json; charset=utf-8", jsonOf("error", "记录不存在"));
                    } else {
                        send(exchange, 200, "application/json; charset=utf-8", Json.write(saved).getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IllegalArgumentException e) {
                    send(exchange, 400, "application/json; charset=utf-8", jsonOf("error", e.getMessage()));
                }
                return;
            }
            if ("DELETE".equals(method)) {
                int status = synchronizedDelete(id) ? 200 : 404;
                byte[] body = status == 200
                        ? "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)
                        : jsonOf("error", "记录不存在");
                send(exchange, status, "application/json; charset=utf-8", body);
                return;
            }
            send(exchange, 405, "application/json; charset=utf-8", jsonOf("error", "方法不允许"));
            return;
        }

        send(exchange, 404, "application/json; charset=utf-8", jsonOf("error", "接口不存在"));
    }

    private static void handleStatic(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            send(exchange, 405, "text/plain; charset=utf-8", "405 Method Not Allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }
        Path target = webRoot.resolve(path.substring(1)).normalize();
        boolean outsideRoot = !target.startsWith(webRoot);
        boolean isDataFile = !outsideRoot
                && webRoot.relativize(target).toString().replace('\\', '/').startsWith("data/");
        if (outsideRoot || isDataFile || !Files.isRegularFile(target)) {
            send(exchange, 404, "text/plain; charset=utf-8", "404 Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = target.getFileName().toString().toLowerCase();
        String type = contentType(name);
        if (type == null) {
            send(exchange, 404, "text/plain; charset=utf-8", "404 Not Found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] bytes = Files.readAllBytes(target);
        if ("HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        send(exchange, 200, type, bytes);
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".ico")) return "image/x-icon";
        return null;
    }

    // ---------- 数据 ----------

    private static Map<String, Object> synchronizedCreate(Map<String, Object> input) throws IOException {
        Map<String, Object> record = normalize(input, null);
        synchronized (LOCK) {
            RECORDS.add(record);
            save();
            return new LinkedHashMap<>(record);
        }
    }

    private static Map<String, Object> synchronizedUpdate(String id, Map<String, Object> input) throws IOException {
        Map<String, Object> replacement = normalize(input, id);
        synchronized (LOCK) {
            for (int i = 0; i < RECORDS.size(); i++) {
                if (id.equals(RECORDS.get(i).get("id"))) {
                    RECORDS.set(i, replacement);
                    save();
                    return new LinkedHashMap<>(replacement);
                }
            }
            return null;
        }
    }

    private static boolean synchronizedDelete(String id) throws IOException {
        synchronized (LOCK) {
            boolean removed = RECORDS.removeIf(r -> id.equals(r.get("id")));
            if (removed) {
                save();
            }
            return removed;
        }
    }

    private static Map<String, Object> normalize(Map<String, Object> input, String forcedId) {
        if (input == null) {
            throw new IllegalArgumentException("请求体不是 JSON 对象");
        }
        String id = forcedId != null ? forcedId : asString(input.get("id"));
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("非法 id");
        }

        String type = asString(input.get("type"));
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("type 需为 expense 或 income");
        }

        Object rawAmount = input.get("amount");
        if (!(rawAmount instanceof Number)) {
            throw new IllegalArgumentException("amount 需为数字");
        }
        double amount = Math.round(((Number) rawAmount).doubleValue() * 100) / 100.0;
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("amount 需为非负数字");
        }
        if (amount > 99_999_999) {
            throw new IllegalArgumentException("amount 超出范围");
        }

        String time = asString(input.get("time"));
        if (time == null || !TIME_PATTERN.matcher(time).matches()) {
            throw new IllegalArgumentException("time 需为 YYYY-MM-DDTHH:MM");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("type", type);
        out.put("amount", amount);
        out.put("category", clamp(asString(input.get("category")), 40));
        out.put("note", clamp(asString(input.get("note")), 500));
        out.put("time", time);
        return out;
    }

    private static String clamp(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static void load() throws IOException {
        if (!Files.isRegularFile(dataFile)) {
            return;
        }
        String text = Files.readString(dataFile, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return;
        }
        Object parsed = Json.parse(text);
        if (parsed instanceof List) {
            synchronized (LOCK) {
                for (Object item : (List<?>) parsed) {
                    if (item instanceof Map) {
                        try {
                            RECORDS.add(normalize(asMap(item), null));
                        } catch (IllegalArgumentException ignored) {
                            // 跳过损坏的历史记录，保证服务仍可启动
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static void save() throws IOException {
        Path dir = dataFile.getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve("records.json.tmp");
        Files.writeString(tmp, Json.write(RECORDS), StandardCharsets.UTF_8);
        Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---------- 工具 ----------

    private static Map<String, Object> readObject(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = exchange.getRequestBody()) {
            byte[] chunk = new byte[4096];
            int read;
            int total = 0;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > 256 * 1024) {
                    throw new IOException("请求体过大");
                }
                buffer.write(chunk, 0, read);
            }
        }
        String text = buffer.toString(StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = text.isBlank() ? null : Json.parse(text);
        } catch (RuntimeException e) {
            throw new IOException("JSON 解析失败");
        }
        if (parsed instanceof Map) {
            return asMap(parsed);
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        return empty;
    }

    private static byte[] jsonOf(String key, String value) {
        StringBuilder sb = new StringBuilder("{");
        Json.writeString(sb, key);
        sb.append(':');
        Json.writeString(sb, value);
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length == 0) {
            return;
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String decode(String path) {
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    /** 极简 JSON 读写，避免引入外部依赖。 */
    private static final class Json {

        private final String src;
        private int pos;

        private Json(String src) {
            this.src = src;
        }

        static Object parse(String text) {
            Json reader = new Json(text);
            reader.skipWhitespace();
            Object value = reader.readValue();
            reader.skipWhitespace();
            if (reader.pos < reader.src.length()) {
                throw new IllegalArgumentException("JSON 结尾存在多余内容");
            }
            return value;
        }

        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            writeValue(sb, value);
            return sb.toString();
        }

        static void writeString(StringBuilder sb, String value) {
            sb.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }

        private static void writeValue(StringBuilder sb, Object value) {
            if (value == null) {
                sb.append("null");
            } else if (value instanceof String) {
                writeString(sb, (String) value);
            } else if (value instanceof Boolean) {
                sb.append(value.toString());
            } else if (value instanceof Number) {
                sb.append(trimNumber(((Number) value).doubleValue()));
            } else if (value instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(sb, String.valueOf(entry.getKey()));
                    sb.append(':');
                    writeValue(sb, entry.getValue());
                }
                sb.append('}');
            } else if (value instanceof List) {
                sb.append('[');
                boolean first = true;
                for (Object item : (List<?>) value) {
                    if (!first) sb.append(',');
                    first = false;
                    writeValue(sb, item);
                }
                sb.append(']');
            } else {
                writeString(sb, String.valueOf(value));
            }
        }

        private static String trimNumber(double value) {
            if (!Double.isFinite(value)) {
                return "0";
            }
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            return src.charAt(pos);
        }

        private Object readValue() {
            skipWhitespace();
            char c = peek();
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new IllegalArgumentException("JSON 对象格式错误");
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new IllegalArgumentException("JSON 数组格式错误");
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw new IllegalArgumentException("JSON 字符串未闭合");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw new IllegalArgumentException("JSON \\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("非法转义 \\" + esc);
                }
            }
        }

        private Object readBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("JSON 字面量错误");
        }

        private Object readNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("JSON 字面量错误");
        }

        private Double readNumber() {
            int start = pos;
            if (peek() == '-' || peek() == '+') pos++;
            while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            String token = src.substring(start, pos);
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("JSON 数字格式错误: " + token);
            }
        }

        private void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("期望字符 " + c);
            }
            pos++;
        }
    }
}
